// MARK: - CarPlay queue — Now Playing's "Up Next" button and its list

import CarPlay
import Observation
import UIKit
import JellyfinMusicCore

/// Now Playing's "Queue" button (CarPlay's Up Next button) and the list it
/// opens: what plays after the current track, a section per album or
/// playlist (one section in play order while shuffled), built by
/// `CarPlayQueueList` within CarPlay's list limits. Tapping a track plays it
/// and goes back to Now Playing; in Maniac Mode the rows do nothing. The
/// button and the open list follow the queue as it changes (edits on the
/// phone, the next track starting).
@MainActor
final class CarPlayQueueScreen: NSObject, CPNowPlayingTemplateObserver {

    private weak var interfaceController: CPInterfaceController?
    /// The open queue list; nil once it has been popped.
    private var template: CPListTemplate?
    /// What the open list shows, so an unchanged queue isn't redrawn.
    private var shown: CarPlayQueueList?
    private var isAttached = false

    private var session: AppSession { AppSession.shared }

    init(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
    }

    func attach() {
        guard !isAttached else { return }
        isAttached = true
        let nowPlaying = CPNowPlayingTemplate.shared
        nowPlaying.upNextTitle = CarPlayQueueList.title
        nowPlaying.add(self)
        observe()
    }

    func detach() {
        isAttached = false
        CPNowPlayingTemplate.shared.remove(self)
        template = nil
        shown = nil
    }

    // MARK: CPNowPlayingTemplateObserver

    nonisolated func nowPlayingTemplateUpNextButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        MainActor.assumeIsolated { show() }
    }

    nonisolated func nowPlayingTemplateAlbumArtistButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {}

    // MARK: The list

    private func show() {
        guard let interfaceController else { return }
        if let template, interfaceController.templates.contains(where: { $0 === template }) { return }
        let template = CPListTemplate(title: CarPlayQueueList.title, sections: [])
        self.template = template
        shown = nil
        render()
        interfaceController.pushTemplate(template, animated: true, completion: nil)
    }

    /// Re-renders when anything the button or the list shows changes, for as
    /// long as CarPlay is connected.
    private func observe() {
        guard isAttached else { return }
        withObservationTracking {
            let player = session.player
            _ = player?.playQueue
            _ = player?.maniacMode
        } onChange: { [weak self] in
            // Called before the change lands: read the new state on the next turn.
            Task { @MainActor [weak self] in
                guard let self, self.isAttached else { return }
                self.render()
                self.observe()
            }
        }
        render()
    }

    private func render() {
        let player = session.player
        let list = player.map {
            CarPlayQueueList(queue: $0.playQueue, maniacMode: $0.maniacMode,
                             maxItems: Int(CPListTemplate.maximumItemCount),
                             maxSections: Int(CPListTemplate.maximumSectionCount))
        }
        CPNowPlayingTemplate.shared.isUpNextButtonEnabled = !(list?.isEmpty ?? true)

        guard let template else { return }
        // Popped (Back): stop updating it.
        if let interfaceController, shown != nil,
           !interfaceController.templates.contains(where: { $0 === template }) {
            self.template = nil
            shown = nil
            return
        }
        guard let list else {
            template.updateSections([])
            template.emptyViewTitleVariants = [CarPlayQueueList.emptyTitle]
            return
        }
        guard list != shown else { return }
        shown = list
        template.updateSections(list.sections.map { section($0, readOnly: list.isReadOnly) })
        template.emptyViewTitleVariants = [CarPlayQueueList.emptyTitle]
        template.emptyViewSubtitleVariants = [CarPlayQueueList.emptySubtitle]
    }

    private func section(_ section: CarPlayQueueList.Section, readOnly: Bool) -> CPListSection {
        let items = section.rows.map { item($0, readOnly: readOnly) }
        guard let header = section.header else { return CPListSection(items: items) }
        return CPListSection(items: items, header: header, headerSubtitle: section.headerDetail,
                             headerImage: nil, headerButton: nil, sectionIndexTitle: nil)
    }

    private func item(_ row: CarPlayQueueList.Row, readOnly: Bool) -> CPListItem {
        let item = CPListItem(text: row.text, detailText: row.detail)
        // Maniac Mode, the note and "…and N more": nothing happens on a tap.
        guard case .entry(let entryId) = row.kind, !readOnly else { return item }
        item.handler = { [weak self] _, done in
            self?.play(entryId: entryId)
            done()
        }
        return item
    }

    /// Plays the tapped entry and goes back to Now Playing.
    private func play(entryId: String) {
        session.player?.skipTo(entryId: entryId)
        guard let interfaceController else { return }
        template = nil
        shown = nil
        // The list is only opened from Now Playing, so it's underneath.
        if interfaceController.templates.contains(where: { $0 === CPNowPlayingTemplate.shared }) {
            interfaceController.pop(to: CPNowPlayingTemplate.shared, animated: true, completion: nil)
        } else {
            interfaceController.popTemplate(animated: true, completion: nil)
        }
    }
}
