import Foundation
import AVFoundation
import UIKit

struct Track {
    var id: Int
    var rel: String          // path relative to Documents (the container path changes between installs)
    var title: String
    var artist: String
    var album: String
    var dur: Double          // ms
    var genre: String
    var hasArt: Bool
    var size: UInt64
    var mtime: Double
    var meta: Bool
}

/// Song library: scans Documents (the "Air.INC" folder in the Files app + imported songs), reads tags/artwork with AVFoundation.
final class LibraryStore {
    static let exts: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "mp4", "m4b"]
    let docs: URL, music: URL, artDir: URL, lyrDir: URL, memDir: URL, cacheFile: URL
    private(set) var tracks: [Track] = []
    private var byId: [Int: Track] = [:]
    private let lock = NSLock()
    private var scanning = false

    init() {
        let fm = FileManager.default
        docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        music = docs.appendingPathComponent("Music", isDirectory: true)
        artDir = lib.appendingPathComponent("Caches/art", isDirectory: true)
        lyrDir = lib.appendingPathComponent("lyrics", isDirectory: true)
        memDir = lib.appendingPathComponent("memories", isDirectory: true)
        cacheFile = lib.appendingPathComponent("library.json")
        for d in [music, artDir, lyrDir, memDir] { try? fm.createDirectory(at: d, withIntermediateDirectories: true) }
        loadCache()
    }

    static func idOf(_ rel: String) -> Int {
        var h: UInt32 = 2166136261
        for b in rel.lowercased().utf8 { h ^= UInt32(b); h = h &* 16777619 }
        let v = Int(h & 0x7fffffff)
        return v == 0 ? 1 : v
    }

    func pub(_ t: Track) -> [String: Any] {
        return ["id": t.id, "title": t.title, "artist": t.artist, "album": t.album, "aid": 0, "dur": t.dur, "genre": t.genre,
                "art": t.hasArt ? "/art/\(t.id).jpg" : ""]
    }
    func publicList() -> [[String: Any]] { lock.lock(); defer { lock.unlock() }; return tracks.map(pub) }
    func url(forId id: Int) -> URL? { lock.lock(); defer { lock.unlock() }; return byId[id].map { docs.appendingPathComponent($0.rel) } }
    func track(_ id: Int) -> Track? { lock.lock(); defer { lock.unlock() }; return byId[id] }

    private func set(_ a: [Track]) {
        lock.lock(); tracks = a; byId = Dictionary(a.map { ($0.id, $0) }, uniquingKeysWith: { x, _ in x }); lock.unlock()
    }

    private func loadCache() {
        guard let d = try? Data(contentsOf: cacheFile), let arr = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return }
        var out: [Track] = []
        for o in arr {
            guard let rel = o["rel"] as? String, FileManager.default.fileExists(atPath: docs.appendingPathComponent(rel).path) else { continue }
            out.append(Track(id: LibraryStore.idOf(rel), rel: rel, title: o["title"] as? String ?? "", artist: o["artist"] as? String ?? "", album: o["album"] as? String ?? "",
                             dur: o["dur"] as? Double ?? 0, genre: o["genre"] as? String ?? "", hasArt: o["art"] as? Bool ?? false,
                             size: UInt64(o["size"] as? Double ?? 0), mtime: o["mtime"] as? Double ?? 0, meta: o["meta"] as? Bool ?? false))
        }
        set(out)
    }

    private func saveCache() {
        let arr: [[String: Any]] = tracks.map { ["rel": $0.rel, "title": $0.title, "artist": $0.artist, "album": $0.album, "dur": $0.dur, "genre": $0.genre,
                                                 "art": $0.hasArt, "size": Double($0.size), "mtime": $0.mtime, "meta": $0.meta] }
        if let d = try? JSONSerialization.data(withJSONObject: arr) { try? d.write(to: cacheFile, options: .atomic) }
    }

    private func guess(_ file: URL) -> (String, String) {
        let base = file.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: " ")
        if let r = base.range(of: " - ") {
            let a = String(base[..<r.lowerBound]).trimmingCharacters(in: .whitespaces), t = String(base[r.upperBound...]).trimmingCharacters(in: .whitespaces)
            if !a.isEmpty && !t.isEmpty { return (t, a) }
        }
        return (base, "")
    }

    /// Two passes: an instant list from file names, then tags + artwork. `cb(list, done)` is called on a background queue.
    func scan(_ cb: @escaping ([[String: Any]], Bool) -> Void) {
        lock.lock(); if scanning { lock.unlock(); return }; scanning = true; lock.unlock()
        DispatchQueue.global(qos: .utility).async {
            defer { self.lock.lock(); self.scanning = false; self.lock.unlock() }
            let fm = FileManager.default
            let old = Dictionary(self.tracks.map { ($0.rel, $0) }, uniquingKeysWith: { x, _ in x })
            var found: [Track] = []
            let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            if let en = fm.enumerator(at: self.docs, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) {
                for case let u as URL in en {
                    guard LibraryStore.exts.contains(u.pathExtension.lowercased()) else { continue }
                    let v = try? u.resourceValues(forKeys: Set(keys))
                    guard v?.isRegularFile == true else { continue }
                    let rel = String(u.path.dropFirst(self.docs.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    let size = UInt64(v?.fileSize ?? 0), mt = v?.contentModificationDate?.timeIntervalSince1970 ?? 0
                    if let o = old[rel], o.size == size, abs(o.mtime - mt) < 1, o.meta { found.append(o); continue }
                    let g = self.guess(u)
                    found.append(Track(id: LibraryStore.idOf(rel), rel: rel, title: g.0, artist: g.1, album: u.deletingLastPathComponent().lastPathComponent == "Documents" ? "" : u.deletingLastPathComponent().lastPathComponent,
                                       dur: 0, genre: "", hasArt: false, size: size, mtime: mt, meta: false))
                }
            }
            found.sort { $0.title.lowercased() < $1.title.lowercased() }
            self.set(found)
            cb(found.map(self.pub), false)
            var n = 0
            for i in 0..<found.count where !found[i].meta {
                self.readMeta(&found[i])
                n += 1
                if n % 40 == 0 { self.set(found); cb(found.map(self.pub), false) }
            }
            found.sort { $0.title.lowercased() < $1.title.lowercased() }
            self.set(found)
            self.saveCache()
            cb(found.map(self.pub), true)
        }
    }

    private func readMeta(_ t: inout Track) {
        let url = docs.appendingPathComponent(t.rel)
        let asset = AVURLAsset(url: url)
        let sem = DispatchSemaphore(value: 0)
        asset.loadValuesAsynchronously(forKeys: ["commonMetadata", "duration"]) { sem.signal() }
        _ = sem.wait(timeout: .now() + 6)
        let secs = CMTimeGetSeconds(asset.duration)
        if secs.isFinite && secs > 0 { t.dur = secs * 1000 }
        var art: Data?
        for it in asset.commonMetadata {
            guard let k = it.commonKey else { continue }
            if k == .commonKeyTitle, let s = it.stringValue, !s.isEmpty { t.title = s }
            else if k == .commonKeyArtist, let s = it.stringValue, !s.isEmpty { t.artist = s }
            else if k == .commonKeyAlbumName, let s = it.stringValue, !s.isEmpty { t.album = s }
            else if k == .commonKeyType, let s = it.stringValue, !s.isEmpty { t.genre = s }
            else if k == .commonKeyArtwork { art = it.dataValue ?? (it.value as? Data) }
        }
        if let d = art, let img = UIImage(data: d) {
            let m: CGFloat = 480, sc = min(1, m / max(img.size.width, img.size.height))
            let sz = CGSize(width: img.size.width * sc, height: img.size.height * sc)
            UIGraphicsBeginImageContextWithOptions(sz, true, 1)
            img.draw(in: CGRect(origin: .zero, size: sz))
            let out = UIGraphicsGetImageFromCurrentImageContext()
            UIGraphicsEndImageContext()
            if let j = out?.jpegData(compressionQuality: 0.82) {
                try? j.write(to: artDir.appendingPathComponent("\(t.id).jpg"))
                t.hasArt = true
            }
        }
        t.meta = true
    }

    /// Copies picked songs into Documents/Music (unique names) and returns how many were added.
    func importFiles(_ urls: [URL]) -> Int {
        let fm = FileManager.default
        var n = 0
        for u in urls {
            let ok = u.startAccessingSecurityScopedResource(); defer { if ok { u.stopAccessingSecurityScopedResource() } }
            var dest = music.appendingPathComponent(u.lastPathComponent)
            var i = 2
            while fm.fileExists(atPath: dest.path) {
                dest = music.appendingPathComponent(u.deletingPathExtension().lastPathComponent + " (\(i))." + u.pathExtension); i += 1
            }
            if (try? fm.copyItem(at: u, to: dest)) != nil { n += 1 }
        }
        return n
    }
}

// MARK: - lyrics (cache -> .lrc next to the song -> LRCLIB), same behaviour as the Android/Windows builds
enum Lyrics {
    static func http(_ url: String, _ timeout: TimeInterval = 9) -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: timeout)
        req.setValue("Air.INC (iOS)", forHTTPHeaderField: "User-Agent")
        let sem = DispatchSemaphore(value: 0)
        var out: Data?
        URLSession.shared.dataTask(with: req) { d, r, _ in
            if let h = r as? HTTPURLResponse, h.statusCode == 200 { out = d }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + timeout + 2)
        return out
    }
    static func has(_ o: [String: Any]?) -> Bool {
        guard let o = o else { return false }
        return ((o["syncedLyrics"] as? String) ?? "") != "" || ((o["plainLyrics"] as? String) ?? "") != ""
    }
    static func pack(_ s: String?, _ p: String?, _ src: String) -> [String: Any] { ["synced": s ?? "", "plain": p ?? "", "src": src] }
    static func get(_ lib: LibraryStore, id: Int, title: String, artist: String, album: String, dur: Int) -> [String: Any] {
        let cache = lib.lyrDir.appendingPathComponent("\(id).json")
        if let d = try? Data(contentsOf: cache), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
            if (o["none"] as? Bool) != true || Date().timeIntervalSince1970 * 1000 - (o["ts"] as? Double ?? 0) < 3 * 86400000 { return o }
        }
        func save(_ o: [String: Any]) -> [String: Any] {
            if let d = try? JSONSerialization.data(withJSONObject: o) { try? d.write(to: cache) }
            return o
        }
        if let f = lib.url(forId: id) {
            let base = f.deletingPathExtension()
            for ext in ["lrc", "LRC", "txt"] {
                let u = base.appendingPathExtension(ext)
                if let t = try? String(contentsOf: u, encoding: .utf8), !t.isEmpty {
                    return save(t.range(of: "\\[\\d+:\\d+", options: .regularExpression) != nil ? pack(t, "", "file") : pack("", t, "file"))
                }
            }
        }
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~")) ?? "" }
        let q = "track_name=\(enc(title))&artist_name=\(enc(artist))&album_name=\(enc(album))" + (dur > 0 ? "&duration=\(dur)" : "")
        let d1 = http("https://lrclib.net/api/get?" + q)
        var o = d1.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
        if !has(o) {
            o = nil
            if let sd = http("https://lrclib.net/api/search?q=" + enc(title + " " + artist)), let arr = (try? JSONSerialization.jsonObject(with: sd)) as? [[String: Any]] {
                for x in arr where has(x) {
                    let xd = (x["duration"] as? Double) ?? Double(dur)
                    if dur <= 0 || abs(xd - Double(dur)) < 6 { o = x; break }
                }
            } else if http("https://lrclib.net/api/get?" + q) == nil && http("https://lrclib.net/", 5) == nil { return ["err": true] }
        }
        if let o = o { return save(pack(o["syncedLyrics"] as? String, o["plainLyrics"] as? String, "lrclib")) }
        return save(["none": true, "ts": Date().timeIntervalSince1970 * 1000])
    }
}
