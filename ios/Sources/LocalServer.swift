import Foundation
import Network

/// Tiny loopback-only HTTP/1.1 server. The UI, songs (Range aware), artwork and memories are all served from here so the page
/// is one same-origin site (needed for Web Audio's MediaElementSource and seeking in <audio>).
final class LocalServer {
    enum Route {
        case file(URL, String)
        case data(Data, String)
    }

    var route: ((String) -> Route?)?
    private var listener: NWListener?
    private let q = DispatchQueue(label: "air.localserver")

    func start(_ ready: @escaping (UInt16) -> Void) {
        let params = NWParameters.tcp
        params.requiredInterfaceType = .loopback
        guard let l = try? NWListener(using: params) else { return }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { state in
            if case .ready = state, let p = l.port?.rawValue {
                DispatchQueue.main.async { ready(p) }
            }
        }
        l.start(queue: q)
        listener = l
    }

    private func accept(_ c: NWConnection) {
        c.start(queue: q)
        read(c, Data())
    }

    private func read(_ c: NWConnection, _ buf: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, err in
            guard let self = self else { return }
            var b = buf
            if let d = data { b.append(d) }
            if let r = b.range(of: Data("\r\n\r\n".utf8)) {
                self.handle(c, String(data: b[..<r.lowerBound], encoding: .utf8) ?? "")
            } else if done || err != nil || b.count > 65536 {
                c.cancel()
            } else {
                self.read(c, b)
            }
        }
    }

    private func respond(_ c: NWConnection, _ status: Int, _ text: String, _ headers: [String: String], _ body: Data?, _ file: (URL, UInt64, UInt64)?) {
        var h = "HTTP/1.1 \(status) \(text)\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n"
        for (k, v) in headers { h += "\(k): \(v)\r\n" }
        h += "\r\n"
        var head = Data(h.utf8)
        if let b = body { head.append(b) }
        guard let f = file else {
            c.send(content: head, completion: .contentProcessed { _ in c.cancel() })
            return
        }
        c.send(content: head, completion: .contentProcessed { [weak self] err in
            if err != nil { c.cancel(); return }
            self?.stream(c, f.0, f.1, f.2)
        })
    }

    private func stream(_ c: NWConnection, _ url: URL, _ start: UInt64, _ length: UInt64) {
        guard let fh = try? FileHandle(forReadingFrom: url) else { c.cancel(); return }
        fh.seek(toFileOffset: start)
        var left = length
        func next() {
            if left == 0 { fh.closeFile(); c.cancel(); return }
            let n = Int(min(UInt64(256 * 1024), left))
            let d = fh.readData(ofLength: n)
            if d.isEmpty { fh.closeFile(); c.cancel(); return }
            left -= UInt64(d.count)
            c.send(content: d, completion: .contentProcessed { err in
                if err != nil { fh.closeFile(); c.cancel(); return }
                next()
            })
        }
        next()
    }

    private func handle(_ c: NWConnection, _ head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { c.cancel(); return }
        let method = String(parts[0])
        var raw = String(parts[1])
        if let i = raw.firstIndex(of: "?") { raw = String(raw[..<i]) }
        let path = raw.removingPercentEncoding ?? raw
        var hd: [String: String] = [:]
        for l in lines.dropFirst() {
            if let i = l.firstIndex(of: ":") { hd[l[..<i].lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces) }
        }
        if method == "OPTIONS" { respond(c, 204, "No Content", ["Access-Control-Allow-Headers": "*"], nil, nil); return }
        guard method == "GET" || method == "HEAD", !path.contains(".."), let r = route?(path) else {
            respond(c, 404, "Not Found", ["Content-Length": "0"], nil, nil); return
        }
        switch r {
        case .data(let d, let mime):
            respond(c, 200, "OK", ["Content-Type": mime, "Content-Length": String(d.count)], method == "HEAD" ? nil : d, nil)
        case .file(let url, let mime):
            let size = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.uint64Value ?? 0
            var start: UInt64 = 0, end: UInt64 = size > 0 ? size - 1 : 0, partial = false
            if let rg = hd["range"], rg.hasPrefix("bytes=") {
                let spec = rg.dropFirst(6).split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
                if spec.count == 2 {
                    if spec[0].isEmpty, let n = UInt64(spec[1]) { start = n >= size ? 0 : size - n }
                    else if let s = UInt64(spec[0]) { start = s; if let e = UInt64(spec[1]) { end = min(end, e) } }
                    partial = true
                }
                if start >= size || start > end {
                    respond(c, 416, "Range Not Satisfiable", ["Content-Range": "bytes */\(size)", "Content-Length": "0"], nil, nil); return
                }
            }
            let len = size == 0 ? 0 : end - start + 1
            var h = ["Content-Type": mime, "Content-Length": String(len), "Accept-Ranges": "bytes"]
            if partial { h["Content-Range"] = "bytes \(start)-\(end)/\(size)" }
            respond(c, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, nil, method == "HEAD" || len == 0 ? nil : (url, start, len))
        }
    }

    static func mime(_ ext: String) -> String {
        switch ext.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "js": return "text/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json"
        case "svg": return "image/svg+xml"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "ttf": return "font/ttf"
        case "otf": return "font/otf"
        case "woff": return "font/woff"
        case "woff2": return "font/woff2"
        case "mp3": return "audio/mpeg"
        case "m4a", "aac", "mp4": return "audio/mp4"
        case "flac": return "audio/flac"
        case "wav": return "audio/wav"
        case "aif", "aiff": return "audio/aiff"
        case "caf": return "audio/x-caf"
        case "ogg", "oga", "opus": return "audio/ogg"
        case "webm": return "video/webm"
        case "mov": return "video/quicktime"
        case "m4v": return "video/mp4"
        default: return "application/octet-stream"
        }
    }
}
