import UIKit
import AVFoundation

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, options: [])
        try? s.setActive(true)
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = .black
        w.rootViewController = ViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        try? AVAudioSession.sharedInstance().setActive(true)
        ViewController.becameActive()
    }
}
