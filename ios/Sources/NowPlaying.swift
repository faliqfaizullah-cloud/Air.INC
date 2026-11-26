import UIKit
import MediaPlayer
import AVFoundation

/// Lock-screen / Control Center controls + background keep-alive for audio playback.
/// The page (Web Audio engine) stays the source of truth; commands are sent back as the same `link:cmd` events the Windows build uses.
final class NowPlaying {
    static let shared = NowPlaying()
    weak var vc: ViewController?
    private var lastId = -1
    private var art: MPMediaItemArtwork?
    private var bgTask = UIBackgroundTaskIdentifier.invalid
    private var wired = false
    private var lastPlaying = false

    func setup(_ vc: ViewController) {
        self.vc = vc
        guard !wired else { return }
        wired = true
        UIApplication.shared.beginReceivingRemoteControlEvents()
        let c = MPRemoteCommandCenter.shared()
        func cmd(_ cc: MPRemoteCommand, _ a: String, _ o: [String: Any] = [:]) {
            cc.isEnabled = true
            cc.addTarget { [weak self] _ in self?.vc?.emit("link:cmd", [a, o]); return .success }
        }
        cmd(c.playCommand, "resume")
        cmd(c.pauseCommand, "pause")
        cmd(c.togglePlayPauseCommand, "toggle")
        cmd(c.nextTrackCommand, "next")
        cmd(c.previousTrackCommand, "prev")
        c.changePlaybackPositionCommand.isEnabled = true
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let ev = e as? MPChangePlaybackPositionCommandEvent { self?.vc?.emit("link:cmd", ["seek", ["ms": Int(ev.positionTime * 1000)]]) }
            return .success
        }
        c.skipForwardCommand.isEnabled = false
        c.skipBackwardCommand.isEnabled = false

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(interruption(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(route(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
        nc.addObserver(self, selector: #selector(didBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        nc.addObserver(self, selector: #selector(didForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    static func activateSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, options: [])
        try? s.setActive(true)
    }

    @objc private func interruption(_ n: Notification) {
        guard let v = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let t = AVAudioSession.InterruptionType(rawValue: v) else { return }
        if t == .began { vc?.emit("link:cmd", ["pause", [String: Any]()]) }
        else {
            NowPlaying.activateSession()
            let o = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            if AVAudioSession.InterruptionOptions(rawValue: o).contains(.shouldResume) { vc?.emit("link:cmd", ["resume", [String: Any]()]) }
        }
    }
    @objc private func route(_ n: Notification) {
        // headphones unplugged -> pause (like every iOS player)
        guard let v = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt, AVAudioSession.RouteChangeReason(rawValue: v) == .oldDeviceUnavailable else { return }
        vc?.emit("link:cmd", ["pause", [String: Any]()])
    }
    @objc private func didBackground() {
        NowPlaying.activateSession()
        endTask()
        // a little extra run time so the next song can be queued while the screen locks
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "air.audio") { [weak self] in self?.endTask() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in self?.endTask() }
    }
    @objc private func didForeground() { endTask(); NowPlaying.activateSession() }
    private func endTask() { if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid } }

    /// json = the page's player state: {idx, playing, pos, dur, speed, ...}
    func update(_ json: String, lib: LibraryStore) {
        guard let d = json.data(using: .utf8), let s = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return }
        let idx = (s["idx"] as? Int) ?? -1
        let list = lib.tracks
        guard idx >= 0, idx < list.count else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        let t = list[idx]
        let playing = (s["playing"] as? Bool) ?? false
        if t.id != lastId {
            lastId = t.id; art = nil
            if t.hasArt, let img = UIImage(contentsOfFile: lib.artDir.appendingPathComponent("\(t.id).jpg").path) {
                art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            }
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPMediaItemPropertyAlbumTitle: t.album,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: ((s["pos"] as? Double) ?? 0) / 1000,
            MPMediaItemPropertyPlaybackDuration: ((s["dur"] as? Double) ?? t.dur) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? ((s["speed"] as? Double) ?? 1) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0
        ]
        if let a = art { info[MPMediaItemPropertyArtwork] = a }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if #available(iOS 13.0, *) { MPNowPlayingInfoCenter.default().playbackState = playing ? .playing : .paused }
        if playing && !lastPlaying { NowPlaying.activateSession() }
        lastPlaying = playing
    }
}
