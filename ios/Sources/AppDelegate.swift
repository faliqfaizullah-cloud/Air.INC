import UIKit
import AVFoundation

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        NowPlaying.activateSession()
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = .black
        w.rootViewController = ViewController()
        w.makeKeyAndVisible()
        window = w
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        NowPlaying.activateSession()
        ViewController.becameActive()
    }
}
