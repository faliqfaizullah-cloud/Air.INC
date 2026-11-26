import UIKit
import WebKit
import AVFoundation
import MobileCoreServices

final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var t: WKScriptMessageHandler?
    init(_ t: WKScriptMessageHandler) { self.t = t }
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) { t?.userContentController(c, didReceive: m) }
}

final class ViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate, UIDocumentPickerDelegate {
    static weak var current: ViewController?
    static func becameActive() { current?.rescan() }

    var web: WKWebView!
    let lib = LibraryStore()
    let server = LocalServer()
    var port: UInt16 = 0
    var dark = true
    var pickReply: ((Bool) -> Void)?

    override var preferredStatusBarStyle: UIStatusBarStyle { dark ? .lightContent : .default }

    override func viewDidLoad() {
        super.viewDidLoad()
        ViewController.current = self
        view.backgroundColor = UIColor(red: 0.063, green: 0.063, blue: 0.082, alpha: 1)
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        if #available(iOS 10.0, *) { cfg.mediaTypesRequiringUserActionForPlayback = [] }
        else { cfg.requiresUserActionForMediaPlayback = false }
        let uc = WKUserContentController()
        uc.add(WeakHandler(self), name: "air")
        if let u = Bundle.main.url(forResource: "host", withExtension: "js"), let s = try? String(contentsOf: u, encoding: .utf8) {
            var ver = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
            ver = ver.replacingOccurrences(of: "'", with: "")
            uc.addUserScript(WKUserScript(source: "window.__airVer='\(ver)';" + s, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        cfg.userContentController = uc
        web = WKWebView(frame: view.bounds, configuration: cfg)
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        web.isOpaque = false
        web.backgroundColor = view.backgroundColor
        web.scrollView.backgroundColor = view.backgroundColor
        web.scrollView.bounces = false
        if #available(iOS 11.0, *) { web.scrollView.contentInsetAdjustmentBehavior = .never }
        web.navigationDelegate = self
        web.uiDelegate = self
        view.addSubview(web)

        server.route = { [weak self] path in self?.route(path) }
        server.start { [weak self] p in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.port = p
                self.web.load(URLRequest(url: URL(string: "http://127.0.0.1:\(p)/")!))
            }
        }
    }

    // MARK: routing
    func route(_ rawPath: String) -> LocalServer.Route? {
        var p = rawPath
        if let q = p.firstIndex(of: "?") { p = String(p[..<q]) }
        p = p.removingPercentEncoding ?? p
        if p == "/" || p.isEmpty { p = "/index.html" }
        let parts = p.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        if parts.contains("..") { return nil }
        if parts.count == 2 && parts[0] == "media" {
            let id = Int(parts[1].components(separatedBy: ".")[0]) ?? 0
            if let u = lib.url(forId: id) { return .file(u, LocalServer.mime(u.pathExtension)) }
            return nil
        }
        if parts.count == 2 && parts[0] == "art" { let u = lib.artDir.appendingPathComponent(parts[1]); return .file(u, "image/jpeg") }
        if parts.count == 2 && parts[0] == "mem" { let u = lib.memDir.appendingPathComponent(parts[1]); return .file(u, LocalServer.mime(u.pathExtension)) }
        guard let www = Bundle.main.url(forResource: "www", withExtension: nil) else { return nil }
        let f = parts.reduce(www) { $0.appendingPathComponent($1) }
        return .file(f, LocalServer.mime(f.pathExtension))
    }

    // MARK: JS bridge
    /// The page sends ids as strings ("123") and durations as numbers; accept both.
    static func intOf(_ v: Any?) -> Int {
        if let i = v as? Int { return i }
        if let d = v as? Double { return Int(d) }
        if let s = v as? String { return Int(s) ?? Int(Double(s) ?? 0) }
        if let n = v as? NSNumber { return n.intValue }
        return 0
    }
    func jsonLit(_ v: Any) -> String {
        guard JSONSerialization.isValidJSONObject([v]), let d = try? JSONSerialization.data(withJSONObject: [v]), var s = String(data: d, encoding: .utf8) else { return "null" }
        s.removeFirst(); s.removeLast()   // iOS 12 cannot serialise bare fragments, so wrap in an array and strip the brackets
        return s.replacingOccurrences(of: "\u{2028}", with: "\\u2028").replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }
    func reply(_ id: Int, _ ok: Bool, _ v: Any) {
        let js = "__airReply(\(id),\(ok ? "true" : "false"),\(jsonLit(v)))"
        DispatchQueue.main.async { self.web.evaluateJavaScript(js, completionHandler: nil) }
    }
    func emit(_ ch: String, _ args: [Any]) {
        let js = "__airEmit(\(jsonLit(ch)),\(jsonLit(args)))"
        DispatchQueue.main.async { self.web.evaluateJavaScript(js, completionHandler: nil) }
    }
    var asked = false
    func rescan(prompt: Bool = false) {
        guard port != 0 else { return }
        lib.scan { [weak self] list, done in
            guard let self = self else { return }
            self.emit("lib:update", [list, done])
            // nothing found and the user asked for a scan -> offer to pick a folder / songs
            if done && list.isEmpty && prompt && !self.lib.hasFolders && !self.asked { self.asked = true; DispatchQueue.main.async { self.chooser() } }
        }
    }

    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let o = m.body as? [String: Any], let id = o["id"] as? Int, let ch = o["ch"] as? String else { return }
        let a = (o["args"] as? [Any]) ?? []
        switch ch {
        case "lib:load": reply(id, true, lib.publicList())
        case "lib:scan": rescan(prompt: true); reply(id, true, true)
        case "lib:add":
            DispatchQueue.main.async { self.chooser { [weak self] ok in self?.reply(id, true, ok); if ok { self?.rescan() } } }
        case "lib:folders": reply(id, true, lib.folderNames())
        case "lib:remove":
            if let n = a.first as? String { lib.removeFolder(n) }
            rescan(); reply(id, true, true)
        case "lyrics:get":
            let q = (a.first as? [String: Any]) ?? [:]
            DispatchQueue.global(qos: .userInitiated).async {
                let r = Lyrics.get(self.lib, id: ViewController.intOf(q["id"]), title: q["title"] as? String ?? "", artist: q["artist"] as? String ?? "",
                                   album: q["album"] as? String ?? "", dur: ViewController.intOf(q["dur"]))
                self.reply(id, true, r)
            }
        case "lyrics:retry":
            let n = ViewController.intOf(a.first)
            if n != 0 { try? FileManager.default.removeItem(at: lib.lyrDir.appendingPathComponent("\(n).json")) }
            reply(id, true, true)
        case "mem:save":
            let q = (a.first as? [String: Any]) ?? [:]
            let ext = String((q["ext"] as? String ?? "bin").filter { $0.isLetter || $0.isNumber }.prefix(5))
            let stamp = String(Int(Date().timeIntervalSince1970 * 1000), radix: 36) + String(Int.random(in: 1000...9999), radix: 36)
            var out: [String: Any] = ["file": stamp + "." + ext, "thumb": ""]
            if let b = q["bytes"] as? String, let d = Data(base64Encoded: b) { try? d.write(to: lib.memDir.appendingPathComponent(stamp + "." + ext)) }
            if let t = q["thumb"] as? String, let r = t.range(of: "base64,"), let d = Data(base64Encoded: String(t[r.upperBound...])) {
                try? d.write(to: lib.memDir.appendingPathComponent(stamp + "_t.jpg")); out["thumb"] = stamp + "_t.jpg"
            }
            reply(id, true, out)
        case "mem:thumb":
            let q = (a.first as? [String: Any]) ?? [:]
            var n = ""
            if let t = q["thumb"] as? String, let f = q["file"] as? String, let r = t.range(of: "base64,"), let d = Data(base64Encoded: String(t[r.upperBound...])) {
                n = (f as NSString).deletingPathExtension + "_t.jpg"
                try? d.write(to: lib.memDir.appendingPathComponent((n as NSString).lastPathComponent))
            }
            reply(id, true, n)
        case "mem:pick": reply(id, true, [])
        case "mem:del":
            for f in ((a.first as? [Any]) ?? []) { if let s = f as? String, !s.isEmpty { try? FileManager.default.removeItem(at: lib.memDir.appendingPathComponent((s as NSString).lastPathComponent)) } }
            reply(id, true, true)
        case "mem:show": reply(id, true, true)
        case "upd:check":
            let v = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
            reply(id, true, ["t": "check", "err": "repo", "cur": v])
        case "upd:get", "upd:cancel", "upd:install": reply(id, true, "fail")
        case "theme":
            let m = (a.first as? String) ?? "dark"
            DispatchQueue.main.async {
                self.dark = m != "light"
                if #available(iOS 13.0, *) { self.overrideUserInterfaceStyle = self.dark ? .dark : .light }
                self.setNeedsStatusBarAppearanceUpdate()
            }
            reply(id, true, true)
        case "sys:bt":
            DispatchQueue.main.async { if let u = URL(string: "App-Prefs:root=Bluetooth") ?? URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u, options: [:], completionHandler: nil) } }
            reply(id, true, true)
        case "alarm:show", "alarm:armed", "link:state": reply(id, true, true)
        case "link:req": reply(id, true, ["status": 0, "text": ""])
        default: reply(id, false, "unsupported \(ch)")
        }
    }

    // MARK: adding music (Files app)
    enum PickMode { case folder, files }
    var pickMode = PickMode.folder

    func chooser(_ done: ((Bool) -> Void)? = nil) {
        let ac = UIAlertController(title: "Add music", message: "Pick a folder in Files and Air.INC scans it automatically every time you open the app. You can also copy song files into the app.", preferredStyle: .actionSheet)
        ac.addAction(UIAlertAction(title: "Choose a music folder (auto-scan)", style: .default) { _ in self.pick(.folder, done) })
        ac.addAction(UIAlertAction(title: "Import song files (copy)", style: .default) { _ in self.pick(.files, done) })
        ac.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in done?(false) })
        if let p = ac.popoverPresentationController { p.sourceView = view; p.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1); p.permittedArrowDirections = [] }
        present(ac, animated: true, completion: nil)
    }
    func pick(_ mode: PickMode, _ done: ((Bool) -> Void)?) {
        pickMode = mode
        pickReply = done
        let p = mode == .folder
            ? UIDocumentPickerViewController(documentTypes: ["public.folder"], in: .open)
            : UIDocumentPickerViewController(documentTypes: ["public.audio", "public.mp3", "public.mpeg-4-audio"], in: .import)
        if mode == .files { p.allowsMultipleSelection = true }
        p.delegate = self
        present(p, animated: true, completion: nil)
    }
    func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        var ok = false
        if pickMode == .folder { for u in urls where lib.addFolder(u) { ok = true } }
        else { ok = lib.importFiles(urls) > 0 }
        pickReply?(ok); pickReply = nil
    }
    func documentPickerWasCancelled(_ c: UIDocumentPickerViewController) { pickReply?(false); pickReply = nil }

    // MARK: WK
    func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let u = a.request.url, a.navigationType == .linkActivated, u.host != "127.0.0.1" { UIApplication.shared.open(u, options: [:], completionHandler: nil); decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
    func webView(_ w: WKWebView, didFinish n: WKNavigation!) { DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { self.rescan() } }
    func webViewWebContentProcessDidTerminate(_ w: WKWebView) { if port != 0 { w.load(URLRequest(url: URL(string: "http://127.0.0.1:\(port)/")!)) } }
}
