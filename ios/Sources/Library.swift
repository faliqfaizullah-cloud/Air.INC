import Foundation
import AVFoundation
import UIKit

struct Track {
    var id: Int
    var root: String         // "d" = the app's Documents folder, "f<hash>" = a folder the user picked in Files
    var rel: String          // path relative to that root (container paths change between installs, so never store absolute ones)
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

/// Song library. Auto-scans (1) the app's Documents folder (= "On My iPhone > Air.INC" in Files) and
/// (2) any folders the user picked in Files (iCloud Drive, other apps, ...) which are remembered through security-scoped bookmarks.
final class LibraryStore {
    static let exts: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "mp4", "m4b"]
    let docs: URL, music: URL, artDir: URL, lyrDir: URL, memDir: URL, cacheFile: URL
    private(set) var tracks: [Track] = []
    private var byId: [Int: Track] = [:]
    private let lock = NSLock()
    private var scanning = false
    private var folders: [(name: String, key: String, url: URL)] = []
    private let defKey = "airFolders"

    init() {
        let fm = FileManager.default
        docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        music = docs.appendingPathComponent("Music", isDirectory: true)
        artDir = lib.appendingPathComponent("Caches/art", isDirectory: true)
        lyrDir = lib.appendingPathComponent("lyrics", isDirectory: true)
        memDir = lib.appendingPathComponent("memories", isDirectory: true)
        cacheFile = lib.appendingPathComponent("library2.json")
        for d in [music, artDir, lyrDir, memDir] { try? fm.createDirectory(at: d, withIntermediateDirectories: true) }
        resolveFolders()
        loadCache()
    }

    static func idOf(_ rel: String) -> Int {
        var h: UInt32 = 2166136261
        for b in rel.lowercased().utf8 { h ^= UInt32(b); h = h &* 16777619 }
        let v = Int(h & 0x7fffffff)
        return v == 0 ? 1 : v
    }
    static func trackId(_ root: String, _ rel: String) -> Int { root == "d" ? idOf(rel) : idOf(root + "/" + rel) }

    // MARK: folders the user picked in Files
    private func resolveFolders() {
        let saved = (UserDefaults.standard.array(forKey: defKey) as? [[String: Any]]) ?? []
        var keep: [[String: Any]] = []
        folders = []
        for o in saved {
            guard let name = o["name"] as? String, let bm = o["bm"] as? Data else { continue }
            var stale = false
            guard let u = try? URL(resolvingBookmarkData: bm, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { continue }
            _ = u.startAccessingSecurityScopedResource()   // kept open for the life of the app so songs can be streamed
            let key = "f" + String(LibraryStore.idOf(name))
            folders.append((name, key, u))
            var e = o
            if stale, let nb = try? u.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) { e["bm"] = nb }
            keep.append(e)
        }
        UserDefaults.standard.set(keep, forKey: defKey)
    }
    func folderNames() -> [String] { folders.map { $0.name } }
    var hasFolders: Bool { !folders.isEmpty }
    func addFolder(_ u: URL) -> Bool {
        _ = u.startAccessingSecurityScopedResource()
        guard let bm = try? u.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else { return false }
        var name = u.lastPathComponent.isEmpty ? "Folder" : u.lastPathComponent
        if folders.contains(where: { $0.name == name }) { name += " (\(folders.count + 1))" }
        var saved = (UserDefaults.standard.array(forKey: defKey) as? [[String: Any]]) ?? []
        saved.append(["name": name, "bm": bm])
        UserDefaults.standard.set(saved, forKey: defKey)
        folders.append((name, "f" + String(LibraryStore.idOf(name)), u))
        return true
    }
    func removeFolder(_ name: String) {
        var saved = (UserDefaults.standard.array(forKey: defKey) as? [[String: Any]]) ?? []
        saved.removeAll { ($0["name"] as? String) == name }
        UserDefaults.standard.set(saved, forKey: defKey)
        if let f = folders.first(where: { $0.name == name }) { f.url.stopAccessingSecurityScopedResource() }
        folders.removeAll { $0.name == name }
    }
    private func rootURL(_ key: String) -> URL? { key == "d" ? docs : folders.first(where: { $0.key == key })?.url }

    func pub(_ t: Track) -> [String: Any] {
        return ["id": t.id, "title": t.title, "artist": t.artist, "album": t.album, "aid": 0, "dur": t.dur, "genre": t.genre,
                "art": t.hasArt ? "/art/\(t.id).jpg" : ""]
    }
    func publicList() -> [[String: Any]] { lock.lock(); defer { lock.unlock() }; return tracks.map(pub) }
    func url(forId id: Int) -> URL? {
        lock.lock(); defer { lock.unlock() }
        guard let t = byId[id], let r = rootURL(t.root) else { return nil }
        return r.appendingPathComponent(t.rel)
    }
    func track(_ id: Int) -> Track? { lock.lock(); defer { lock.unlock() }; return byId[id] }

    private func set(_ a: [Track]) {
        lock.lock(); tracks = a; byId = Dictionary(a.map { ($0.id, $0) }, uniquingKeysWith: { x, _ in x }); lock.unlock()
    }

    private func loadCache() {
        guard let d = try? Data(contentsOf: cacheFile), let arr = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return }
        var out: [Track] = []
        for o in arr {
            guard let rel = o["rel"] as? String else { continue }
            let root = (o["root"] as? String) ?? "d"
            guard let r = rootURL(root), FileManager.default.fileExists(atPath: r.appendingPathComponent(rel).path) else { continue }
            out.append(Track(id: LibraryStore.trackId(root, rel), root: root, rel: rel, title: o["title"] as? String ?? "", artist: o["artist"] as? String ?? "", album: o["album"] as? String ?? "",
                             dur: o["dur"] as? Double ?? 0, genre: o["genre"] as? String ?? "", hasArt: o["art"] as? Bool ?? false,
                             size: UInt64(o["size"] as? Double ?? 0), mtime: o["mtime"] as? Double ?? 0, meta: o["meta"] as? Bool ?? false))
        }
        set(out)
    }

    private func saveCache() {
        let arr: [[String: Any]] = tracks.map { ["root": $0.root, "rel": $0.rel, "title": $0.title, "artist": $0.artist, "album": $0.album, "dur": $0.dur, "genre": $0.genre,
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

    /// Two passes: an instant list from file names, then tags + artwork (several files at a time). `cb(list, done)` runs on a background queue.
    func scan(_ cb: @escaping ([[String: Any]], Bool) -> Void) {
        lock.lock(); if scanning { lock.unlock(); return }; scanning = true; lock.unlock()
        DispatchQueue.global(qos: .utility).async {
            defer { self.lock.lock(); self.scanning = false; self.lock.unlock() }
            let fm = FileManager.default
            let old = Dictionary(self.tracks.map { ($0.root + "/" + $0.rel, $0) }, uniquingKeysWith: { x, _ in x })
            var found: [Track] = []
            let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            var roots: [(String, URL)] = [("d", self.docs)]
            for f in self.folders { roots.append((f.key, f.url)) }
            for (key, base) in roots {
                guard let en = fm.enumerator(at: base, includingPropertiesForKeys: keys, options: []) else { continue }
                for case let u as URL in en {
                    var name = u.lastPathComponent
                    var real = u
                    if name.hasPrefix(".") && name.hasSuffix(".icloud") && name.count > 8 {   // iCloud file not downloaded yet: ask iOS to fetch it, it shows up on the next scan
                        name = String(name.dropFirst().dropLast(7))
                        real = u.deletingLastPathComponent().appendingPathComponent(name)
                        if LibraryStore.exts.contains(real.pathExtension.lowercased()) { try? fm.startDownloadingUbiquitousItem(at: real) }
                        continue
                    }
                    if name.hasPrefix(".") { if (try? u.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true { en.skipDescendants() }; continue }
                    guard LibraryStore.exts.contains(u.pathExtension.lowercased()) else { continue }
                    let v = try? u.resourceValues(forKeys: Set(keys))
                    guard v?.isRegularFile == true else { continue }
                    let rel = String(u.path.dropFirst(base.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    let size = UInt64(v?.fileSize ?? 0), mt = v?.contentModificationDate?.timeIntervalSince1970 ?? 0
                    if let o = old[key + "/" + rel], o.size == size, abs(o.mtime - mt) < 1, o.meta { found.append(o); continue }
                    let g = self.guess(u)
                    let parent = u.deletingLastPathComponent()
                    found.append(Track(id: LibraryStore.trackId(key, rel), root: key, rel: rel, title: g.0, artist: g.1,
                                       album: parent.path == base.path || parent.lastPathComponent == "Music" ? "" : parent.lastPathComponent,
                                       dur: 0, genre: "", hasArt: false, size: size, mtime: mt, meta: false))
                }
            }
            found.sort { $0.title.lowercased() < $1.title.lowercased() }
            self.set(found)
            cb(found.map(self.pub), false)
            let todo = (0..<found.count).filter { !found[$0].meta }
            if !todo.isEmpty {
                let wl = NSLock()
                var n = 0
                DispatchQueue.concurrentPerform(iterations: todo.count) { k in
                    let i = todo[k]
                    wl.lock(); let t0 = found[i]; wl.unlock()
                    let t = self.readMeta(t0)
                    wl.lock()
                    found[i] = t; n += 1
                    let snap: [Track]? = n % 40 == 0 ? found : nil
                    wl.unlock()
                    if let s = snap { self.set(s); cb(s.map(self.pub), false) }
                }
            }
            found.sort { $0.title.lowercased() < $1.title.lowercased() }
            self.set(found)
            self.saveCache()
            cb(found.map(self.pub), true)
        }
    }

    private func readMeta(_ t0: Track) -> Track {
        var t = t0
        guard let r = rootURL(t.root) else { return t }
        let asset = AVURLAsset(url: r.appendingPathComponent(t.rel))
        let sem = DispatchSemaphore(value: 0)
        asset.loadValuesAsynchronously(forKeys: ["commonMetadata", "duration"]) { sem.signal() }
        _ = sem.wait(timeout: .now() + 8)
        let secs = CMTimeGetSeconds(asset.duration)
        if secs.isFinite && secs > 0 { t.dur = secs * 1000 }
        var art: Data?
        func artData(_ it: AVMetadataItem) -> Data? {
            if let d = it.dataValue, UIImage(data: d) != nil { return d }
            if let d = it.value as? Data, UIImage(data: d) != nil { return d }
            if let dict = it.value as? [AnyHashable: Any], let d = dict["data"] as? Data, UIImage(data: d) != nil { return d }
            return nil
        }
        for it in asset.commonMetadata {
            guard let k = it.commonKey else { continue }
            if k == .commonKeyTitle, let s = it.stringValue, !s.isEmpty { t.title = s }
            else if k == .commonKeyArtist, let s = it.stringValue, !s.isEmpty { t.artist = s }
            else if k == .commonKeyAlbumName, let s = it.stringValue, !s.isEmpty { t.album = s }
            else if k == .commonKeyType, let s = it.stringValue, !s.isEmpty { t.genre = s }
            else if k == .commonKeyArtwork, art == nil { art = artData(it) }
        }
        if art == nil {   // ID3 APIC / iTunes covr that were not mapped to the common keys
            for fmt in asset.availableMetadataFormats {
                for it in asset.metadata(forFormat: fmt) {
                    let id = it.identifier?.rawValue ?? ""
                    let key = (it.key as? String) ?? ""
                    if id.contains("APIC") || id.lowercased().contains("covr") || id.lowercased().contains("artwork") || key == "APIC" || key == "covr" { if let d = artData(it) { art = d; break } }
                }
                if art != nil { break }
            }
        }
        if art == nil {   // folder.jpg / cover.jpg next to the song
            let dir = r.appendingPathComponent(t.rel).deletingLastPathComponent()
            for n in ["cover", "folder", "front", "album", "albumart", "Cover", "Folder"] {
                for e in ["jpg", "jpeg", "png"] {
                    if let d = try? Data(contentsOf: dir.appendingPathComponent(n + "." + e)), UIImage(data: d) != nil { art = d; break }
                }
                if art != nil { break }
            }
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
        return t
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
