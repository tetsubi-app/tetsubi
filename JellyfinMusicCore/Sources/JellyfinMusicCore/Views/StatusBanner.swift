// MARK: - StatusBanner — offline / server-error notice at the top of a screen

import SwiftUI

struct StatusBanner: View {
    let systemImage: String
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: systemImage)
                .foregroundStyle(Color.appPink)
            Text(message)
                .font(.plex(.footnote))
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.plex(.footnote).bold())
                    .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(Color.appGroupBackground, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
        .padding(.top, 8)
        .accessibilityElement(children: .combine)
    }

    /// Shown on screens that need the server while the device is offline.
    static var offline: StatusBanner {
        StatusBanner(systemImage: "wifi.slash",
                     message: "You're offline. Downloaded albums still play from the Downloads tab.")
    }
}
