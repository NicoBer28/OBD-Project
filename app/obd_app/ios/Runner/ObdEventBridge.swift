import Foundation

// El buzón entre Swift y Flutter
class ObdEventBridge {
    static let shared = ObdEventBridge()
    
    var flutterApi: ObdFlutterApi?
}