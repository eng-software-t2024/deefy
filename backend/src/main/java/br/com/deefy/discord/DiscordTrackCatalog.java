package br.com.deefy.discord;

import br.com.deefy.model.Music;
import br.com.deefy.repository.MusicRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DiscordTrackCatalog {

    private static final int AMBIGUITY_PROBE_SIZE = 3;

    private final MusicRepository musicRepository;
    private final DiscordPlaybackProperties properties;

    public DiscordTrackCatalog(MusicRepository musicRepository, DiscordPlaybackProperties properties) {
        this.musicRepository = musicRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TrackLookupResult find(String idOrTitle) {
        String query = normalizeTitle(idOrTitle);
        if (query.isBlank()) {
            return TrackLookupResult.of(TrackLookupStatus.INVALID_QUERY);
        }

        TrackSelection selection = parsePositiveId(query)
                .map(id -> new TrackSelection(
                        musicRepository.findWithArtistById(id),
                        TrackLookupStatus.NOT_FOUND
                ))
                .orElseGet(() -> findByTitle(query));

        if (selection.music().isEmpty()) {
            return TrackLookupResult.of(selection.emptyStatus());
        }

        Music music = selection.music().get();
        if (music.getFileUrl() == null || music.getFileUrl().isBlank()) {
            return TrackLookupResult.of(TrackLookupStatus.MISSING_AUDIO_URL);
        }
        if (!properties.isSelfHostedAudioUrl(music.getFileUrl())) {
            return TrackLookupResult.of(TrackLookupStatus.UNSAFE_SOURCE);
        }

        return new TrackLookupResult(
                TrackLookupStatus.FOUND,
                new DiscordTrack(music.getId(), music.getTitle(), music.getArtist(), music.getFileUrl())
        );
    }

    private TrackSelection findByTitle(String title) {
        List<Music> exactMatches = musicRepository.findByTitleIgnoreCase(title);
        if (exactMatches.size() == 1) {
            return new TrackSelection(Optional.of(exactMatches.getFirst()), TrackLookupStatus.NOT_FOUND);
        }
        if (exactMatches.size() > 1) {
            return new TrackSelection(Optional.empty(), TrackLookupStatus.AMBIGUOUS);
        }

        Page<Music> partialMatches = musicRepository.findByTitleContainingIgnoreCase(
                title,
                PageRequest.of(0, AMBIGUITY_PROBE_SIZE, Sort.by("id").ascending())
        );
        if (partialMatches.getTotalElements() == 1) {
            return new TrackSelection(
                    Optional.of(partialMatches.getContent().getFirst()),
                    TrackLookupStatus.NOT_FOUND
            );
        }
        return new TrackSelection(
                Optional.empty(),
                partialMatches.isEmpty() ? TrackLookupStatus.NOT_FOUND : TrackLookupStatus.AMBIGUOUS
        );
    }

    private String normalizeTitle(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private Optional<Long> parsePositiveId(String query) {
        try {
            long id = Long.parseLong(query);
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public record TrackLookupResult(TrackLookupStatus status, DiscordTrack track) {
        private static TrackLookupResult of(TrackLookupStatus status) {
            return new TrackLookupResult(status, null);
        }
    }

    private record TrackSelection(Optional<Music> music, TrackLookupStatus emptyStatus) {
    }

    public enum TrackLookupStatus {
        FOUND,
        INVALID_QUERY,
        NOT_FOUND,
        AMBIGUOUS,
        MISSING_AUDIO_URL,
        UNSAFE_SOURCE
    }
}
