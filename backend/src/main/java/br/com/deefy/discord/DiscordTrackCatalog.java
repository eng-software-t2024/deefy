package br.com.deefy.discord;

import br.com.deefy.model.Music;
import br.com.deefy.repository.MusicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class DiscordTrackCatalog {

    private static final String TITLE_ARTIST_SEPARATOR = " - ";
    private static final int MAX_AMBIGUITY_CHOICES = 5;
    private static final int MAX_AUTOCOMPLETE_CHOICES = 25;
    private static final int MAX_DISCORD_CHOICE_LENGTH = 100;

    private final MusicRepository musicRepository;
    private final DiscordPlaybackProperties properties;

    public DiscordTrackCatalog(MusicRepository musicRepository, DiscordPlaybackProperties properties) {
        this.musicRepository = musicRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TrackLookupResult find(String idOrTitle) {
        String query = normalize(idOrTitle);
        if (query.isBlank()) {
            return TrackLookupResult.of(TrackLookupStatus.INVALID_QUERY);
        }

        Optional<Long> id = parsePositiveId(query);
        if (id.isPresent()) {
            return musicRepository.findWithArtistById(id.get())
                    .map(this::validateSelectedTrack)
                    .orElseGet(() -> TrackLookupResult.of(TrackLookupStatus.NOT_FOUND));
        }

        List<CatalogEntry> catalog = loadCatalog();

        List<CatalogEntry> exactTitleAndArtist = distinctMatches(
                catalog,
                entry -> titleArtistCandidates(query).stream().anyMatch(entry::matchesExact)
        );
        if (!exactTitleAndArtist.isEmpty()) {
            return resolveMatches(exactTitleAndArtist);
        }

        List<CatalogEntry> exactTitle = distinctMatches(catalog, entry -> entry.matchesExactTitle(query));
        if (!exactTitle.isEmpty()) {
            return resolveMatches(exactTitle);
        }

        List<CatalogEntry> partialTitleAndArtist = distinctMatches(
                catalog,
                entry -> titleArtistCandidates(query).stream().anyMatch(entry::matchesPartial)
        );
        if (!partialTitleAndArtist.isEmpty()) {
            return resolveMatches(partialTitleAndArtist);
        }

        return resolveMatches(distinctMatches(catalog, entry -> entry.matchesPartialTitle(query)));
    }

    @Transactional(readOnly = true)
    public List<TrackChoice> suggest(String value) {
        String query = normalize(value);
        List<CatalogEntry> catalog = loadCatalog();

        return catalog.stream()
                .filter(entry -> query.isBlank() || entry.matchesAutocomplete(query))
                .filter(entry -> entry.music().getFileUrl() != null
                        && !entry.music().getFileUrl().isBlank()
                        && properties.isSelfHostedAudioUrl(entry.music().getFileUrl()))
                .sorted(Comparator
                        .comparingInt((CatalogEntry entry) -> entry.autocompleteRank(query))
                        .thenComparing(entry -> entry.music().getId()))
                .limit(MAX_AUTOCOMPLETE_CHOICES)
                .map(this::toChoice)
                .toList();
    }

    private TrackLookupResult resolveMatches(List<CatalogEntry> matches) {
        if (matches.isEmpty()) {
            return TrackLookupResult.of(TrackLookupStatus.NOT_FOUND);
        }
        if (matches.size() > 1) {
            List<TrackChoice> choices = matches.stream()
                    .limit(MAX_AMBIGUITY_CHOICES)
                    .map(this::toChoice)
                    .toList();
            return new TrackLookupResult(TrackLookupStatus.AMBIGUOUS, null, choices);
        }
        return validateSelectedTrack(matches.getFirst().music());
    }

    private TrackLookupResult validateSelectedTrack(Music music) {
        if (music.getFileUrl() == null || music.getFileUrl().isBlank()) {
            return TrackLookupResult.of(TrackLookupStatus.MISSING_AUDIO_URL);
        }
        if (!properties.isSelfHostedAudioUrl(music.getFileUrl())) {
            return TrackLookupResult.of(TrackLookupStatus.UNSAFE_SOURCE);
        }

        return new TrackLookupResult(
                TrackLookupStatus.FOUND,
                new DiscordTrack(music.getId(), music.getTitle(), music.getArtist(), music.getFileUrl()),
                List.of()
        );
    }

    private List<CatalogEntry> loadCatalog() {
        return musicRepository.findAllByOrderByIdAsc().stream()
                .map(this::toCatalogEntry)
                .toList();
    }

    private CatalogEntry toCatalogEntry(Music music) {
        String storedTitle = safe(music.getTitle());
        String catalogArtist = safe(music.getArtist());
        Set<String> titleAliases = new LinkedHashSet<>();
        Set<String> artistAliases = new LinkedHashSet<>();

        addNormalized(titleAliases, storedTitle);
        addNormalized(artistAliases, catalogArtist);

        int separator = storedTitle.indexOf(TITLE_ARTIST_SEPARATOR);
        if (separator > 0 && separator + TITLE_ARTIST_SEPARATOR.length() < storedTitle.length()) {
            String creditedArtists = storedTitle.substring(0, separator);
            String importedTitle = storedTitle.substring(separator + TITLE_ARTIST_SEPARATOR.length());
            addNormalized(titleAliases, importedTitle);
            addNormalized(artistAliases, creditedArtists);
            for (String creditedArtist : creditedArtists.split(",")) {
                addNormalized(artistAliases, creditedArtist);
            }
        }

        return new CatalogEntry(music, titleAliases, artistAliases);
    }

    private List<CatalogEntry> distinctMatches(List<CatalogEntry> catalog, Predicate<CatalogEntry> predicate) {
        LinkedHashMap<Long, CatalogEntry> matches = new LinkedHashMap<>();
        for (CatalogEntry entry : catalog) {
            if (predicate.test(entry)) {
                matches.putIfAbsent(entry.music().getId(), entry);
            }
        }
        return new ArrayList<>(matches.values());
    }

    private List<TitleArtistCandidate> titleArtistCandidates(String query) {
        List<TitleArtistCandidate> candidates = new ArrayList<>();
        int fromIndex = 0;
        while (fromIndex < query.length()) {
            int separator = query.indexOf(TITLE_ARTIST_SEPARATOR, fromIndex);
            if (separator < 0) {
                break;
            }
            String title = query.substring(0, separator).trim();
            String artist = query.substring(separator + TITLE_ARTIST_SEPARATOR.length()).trim();
            if (!title.isBlank() && !artist.isBlank()) {
                candidates.add(new TitleArtistCandidate(title, artist));
            }
            fromIndex = separator + TITLE_ARTIST_SEPARATOR.length();
        }
        return candidates;
    }

    private TrackChoice toChoice(CatalogEntry entry) {
        String title = safe(entry.music().getTitle());
        String artist = safe(entry.music().getArtist());
        String label = artist.isBlank() ? title : title + " — " + artist;
        return new TrackChoice(
                entry.music().getId(),
                truncate(label, MAX_DISCORD_CHOICE_LENGTH),
                Long.toString(entry.music().getId())
        );
    }

    private void addNormalized(Set<String> values, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private Optional<Long> parsePositiveId(String query) {
        try {
            long id = Long.parseLong(query);
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public record TrackLookupResult(
            TrackLookupStatus status,
            DiscordTrack track,
            List<TrackChoice> choices
    ) {
        private static TrackLookupResult of(TrackLookupStatus status) {
            return new TrackLookupResult(status, null, List.of());
        }
    }

    public record TrackChoice(long id, String label, String value) {
    }

    private record CatalogEntry(Music music, Set<String> titleAliases, Set<String> artistAliases) {
        private boolean matchesExact(TitleArtistCandidate candidate) {
            return titleAliases.contains(candidate.title()) && artistAliases.contains(candidate.artist());
        }

        private boolean matchesPartial(TitleArtistCandidate candidate) {
            return titleAliases.stream().anyMatch(alias -> alias.contains(candidate.title()))
                    && artistAliases.stream().anyMatch(alias -> alias.contains(candidate.artist()));
        }

        private boolean matchesExactTitle(String query) {
            return titleAliases.contains(query);
        }

        private boolean matchesPartialTitle(String query) {
            return titleAliases.stream().anyMatch(alias -> alias.contains(query));
        }

        private boolean matchesAutocomplete(String query) {
            return matchesPartialTitle(query)
                    || artistAliases.stream().anyMatch(alias -> alias.contains(query))
                    || titleAliases.stream().anyMatch(title -> artistAliases.stream()
                    .anyMatch(artist -> (title + " - " + artist).contains(query)));
        }

        private int autocompleteRank(String query) {
            if (query.isBlank()) {
                return 3;
            }
            if (titleAliases.contains(query)) {
                return 0;
            }
            if (titleAliases.stream().anyMatch(alias -> alias.startsWith(query))) {
                return 1;
            }
            return 2;
        }
    }

    private record TitleArtistCandidate(String title, String artist) {
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
