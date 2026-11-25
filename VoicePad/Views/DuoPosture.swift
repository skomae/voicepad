import CoreGraphics

/// How much screen we have to work with.
///
/// A foldable changes this under the app: the Duo's outer display is 466 x 678 pt
/// and its inner one 669 x 951 pt, so the two never overlap. iOS reports no hinge
/// angle (there is no such API in the SDK), but it does resize the scene, which is
/// all the fold event has to tell us.
enum DuoPosture: Equatable {
    /// A folded phone, or any iPhone held upright: one column, notes list.
    case compact
    /// Unfolded, or an iPad: enough room for the tabletop layout.
    case wide

    /// Where a column stops being a phone column. The Duo's outer display is
    /// 466 pt on its short side and its inner one 669 pt, so the boundary sits
    /// between them and a resize across it is the fold.
    static let wideThreshold: CGFloat = 600

    static func of(_ size: CGSize) -> DuoPosture {
        guard size.width > 0, size.height > 0 else { return .compact }
        return min(size.width, size.height) >= wideThreshold ? .wide : .compact
    }

    /// Landscape in a wide window: the posture a half-open laptop has, and the one
    /// the tabletop pad is built for.
    static func isLandscape(_ size: CGSize) -> Bool { size.width > size.height }
}
