import SwiftUI

/// Settings → Theme.
enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark, black

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        case .black: return "Pure black (OLED)"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark, .black: return .dark
        }
    }
}

private struct PureBlackKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// Pure-black (OLED) theme: backgrounds and bars #000; cards stay near-black grey.
    var pureBlack: Bool {
        get { self[PureBlackKey.self] }
        set { self[PureBlackKey.self] = newValue }
    }
}
