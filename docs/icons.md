# Icons

The app icon is a disc on navy (`#000B25`). The disc's SVG is
`Tetsubi-iOS/Assets.xcassets/DiscLogo.imageset/DiscLogo.svg` (the same file is in
the Mac and widget catalogs). The icons are drawn from it with
`tools/render-disc.swift` (NSImage renders the SVG's gradient; ImageMagick drops
it). The logo inside the apps is the SVG itself, so it stays sharp at any size:
`DiscLogo` in the iOS, Mac and widget asset catalogs (vector, "preserve vector
data"), and `Kotlin/shared/src/commonMain/composeResources/drawable/disc.xml`, a
hand-converted Android vector drawable of the same artwork (the Settings and
sign-in screens, and the desktop window icon). The SVG's disc is 880 of its 1000
viewBox, so the SVG is drawn at disc size ÷ 0.88, centred.

The disc is the same size as the Jellyfin logo in Jellyfin's own icons, so the
two look alike side by side.

| Icon | Files | Canvas | Disc |
|---|---|---|---|
| iOS | `Tetsubi-iOS/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (no alpha channel) | 1024 px, navy | 746 px |
| Mac | `Tetsubi-macOS/Assets.xcassets/AppIcon.appiconset/icon_*.png` (16 to 1024, each drawn from the SVG) | full square, navy | 746 of 1024 |
| Linux | `Kotlin/desktopApp/icons/tetsubi.png` | 512 px, navy | 373 px |
| Android foreground | `Kotlin/androidApp/src/main/res/mipmap-*/ic_launcher_foreground.png` | 108 dp, transparent | 45.9 dp |
| Android monochrome | `Kotlin/androidApp/src/main/res/mipmap-*/ic_launcher_monochrome.png` | 108 dp, transparent | 45.9 dp |

- macOS fits the full-square image into its own rounded tile (824 of 1024), so
  the disc shows at about 600 px there.
- Android densities: mdpi 108 px, hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432.
  The background layer is the colour `tetsubi_navy`.
- The monochrome layer is a white disc with see-through rings: draw the SVG
  with a white fill and black rings, then use luminance × alpha as the alpha of
  a white square.
- Jellyfin's sizes were measured from the Jellyfin Mobile icon on the App Store
  (746 px logo in 1024) and the launcher foreground in `jellyfin-android`
  (435 of a 1024 viewport on 108 dp).
