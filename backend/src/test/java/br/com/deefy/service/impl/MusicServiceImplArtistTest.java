package br.com.deefy.service.impl;

import br.com.deefy.dto.response.MusicListResponseDTO;
import br.com.deefy.exception.ArtistNotFoundException;
import br.com.deefy.mapper.MusicMapper;
import br.com.deefy.model.Artist;
import br.com.deefy.model.Music;
import br.com.deefy.repository.ArtistRepository;
import br.com.deefy.repository.MusicRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicServiceImplArtistTest {

    @Mock
    private MusicRepository musicRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private MusicMapper musicMapper;

    @InjectMocks
    private MusicServiceImpl musicService;

    @Test
    void findByArtistId_Success() {
        Pageable pageable = PageRequest.of(0, 50);
        Artist artist = new Artist(1L, "Artist Test");
        Music music = new Music(1L, "Test Music", "Rock", 180, artist);
        MusicListResponseDTO dto = new MusicListResponseDTO(
                1L, "Test Music", "Artist Test", "Rock", 180, "3:00",
                "http://example.com/cover.jpg", null, "http://example.com/file.mp3"
        );

        when(artistRepository.existsById(1L)).thenReturn(true);
        when(musicRepository.findByArtistId(1L, pageable)).thenReturn(new PageImpl<>(List.of(music), pageable, 1));
        when(musicMapper.toListDTO(music)).thenReturn(dto);

        Page<MusicListResponseDTO> result = musicService.findByArtistId(1L, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("Test Music", result.getContent().get(0).title());
        verify(musicRepository).findByArtistId(1L, pageable);
    }

    @Test
    void findByArtistId_ArtistNotFound_ThrowsException() {
        Pageable pageable = PageRequest.of(0, 50);
        when(artistRepository.existsById(99L)).thenReturn(false);

        assertThrows(ArtistNotFoundException.class, () -> musicService.findByArtistId(99L, pageable));

        verify(musicRepository, never()).findByArtistId(99L, pageable);
        verifyNoInteractions(musicMapper);
    }
}