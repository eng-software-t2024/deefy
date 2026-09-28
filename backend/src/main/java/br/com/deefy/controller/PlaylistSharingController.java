package br.com.deefy.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/playlists/{playlistId}/sharing")
public class PlaylistSharingController {

    public ResponseEntity<void> sharePlaylist() {
    }
}
