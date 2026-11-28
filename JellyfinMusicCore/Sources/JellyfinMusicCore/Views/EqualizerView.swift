// MARK: - EqualizerView — 10-band equaliser with presets

import SwiftUI

/// The equaliser: on/off, a preset, and ten bands. A pushed page on compact
/// (`embedded` false: its own header with Back), a section of Settings'
/// Playback on wide (`embedded` true: just the groups).
public struct EqualizerView: View {
    @State private var enabled = AudioEffects.shared.equalizerEnabled
    @State private var gains = AudioEffects.shared.equalizerGains
    @State private var preset = PlaybackSettings.equalizerPreset

    private let embedded: Bool
    @Environment(\.dismiss) private var dismiss

    public init(embedded: Bool = false) { self.embedded = embedded }

    public var body: some View {
        Group {
            if embedded {
                groups
            } else {
                VStack(spacing: 0) {
                    PageHeader("Equaliser", onBack: { dismiss() })
                    ScrollView {
                        groups
                            .padding(.top, Spacing.s)
                            .padding(.bottom, Spacing.xxl)
                    }
                }
                .ownsItsHeader(isDetail: true)
            }
        }
        .onChange(of: preset) { _, new in
            PlaybackSettings.equalizerPreset = new
            if let presetGains = new.gains { gains = presetGains }
        }
        .onChange(of: enabled) { _, _ in apply() }
        .onChange(of: gains) { _, _ in apply() }
    }

    private var groups: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            SettingsGroup(
                title: embedded ? "Equaliser" : nil,
                footer: "Changes apply straight away. When bands are boosted the overall level is lowered to avoid distortion. While the equaliser is on, there's a short pause between tracks — turn it off for gapless playback. The equaliser doesn't apply when playing over AirPlay."
            ) {
                SettingsToggleRow("Equaliser", isOn: $enabled)
                SettingsMenuRow("Preset", selection: $preset,
                                options: EqualizerPreset.allCases.map { ($0, $0.displayName) }, divider: false)
                    .opacity(enabled ? 1 : 0.4)
                    .disabled(!enabled)
            }
            SettingsGroup(title: "Bands (dB)") {
                HStack(alignment: .bottom, spacing: 0) {
                    ForEach(AudioEffects.frequencies.indices, id: \.self) { i in
                        VStack(spacing: Spacing.s) {
                            Text(gains[i] > 0 ? "+\(Int(gains[i].rounded()))" : "\(Int(gains[i].rounded()))")
                                .font(TetsubiType.navLabel.monospacedDigit())
                                .foregroundStyle(TetsubiColors.textSecondary)
                            VerticalSlider(value: Binding(
                                get: { gains[i] },
                                set: { gains[i] = $0; preset = .custom }
                            ), range: AudioEffects.gainRange)
                            .frame(height: 180)
                            .accessibilityLabel("\(Self.label(AudioEffects.frequencies[i])) hertz")
                            Text(Self.label(AudioEffects.frequencies[i]))
                                .font(TetsubiType.navLabel)
                                .foregroundStyle(TetsubiColors.textSecondary)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, Spacing.l)
                .opacity(enabled ? 1 : 0.4)
                .disabled(!enabled)
            }
        }
    }

    private func apply() {
        AudioEffects.shared.setEqualizer(enabled: enabled, gains: gains)
    }

    /// "31", "500", "1k", "16k".
    static func label(_ hz: Float) -> String {
        hz >= 1000 ? "\(Int(hz / 1000))k" : "\(Int(hz))"
    }
}

/// A vertical slider: fill grows up or down from 0 dB; snaps to 0.5 dB.
struct VerticalSlider: View {
    @Binding var value: Float
    let range: ClosedRange<Float>

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            let span = range.upperBound - range.lowerBound
            let y: (Float) -> CGFloat = { CGFloat(1 - ($0 - range.lowerBound) / span) * height }
            ZStack(alignment: .top) {
                Capsule().fill(TetsubiColors.art).frame(width: 4)
                // Fill from the 0 dB line to the value.
                Capsule()
                    .fill(TetsubiColors.blue)
                    .frame(width: 4, height: abs(y(value) - y(0)))
                    .offset(y: min(y(value), y(0)))
                Circle()
                    .fill(TetsubiColors.textPrimary)
                    .frame(width: 18, height: 18)
                    .offset(y: y(value) - 9)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                let fraction = Float(1 - min(max(drag.location.y / height, 0), 1))
                value = ((range.lowerBound + fraction * span) * 2).rounded() / 2
            })
        }
        .accessibilityElement()
        .accessibilityValue("\(value, specifier: "%.1f") decibels")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: value = min(range.upperBound, value + 1)
            case .decrement: value = max(range.lowerBound, value - 1)
            @unknown default: break
            }
        }
    }
}
