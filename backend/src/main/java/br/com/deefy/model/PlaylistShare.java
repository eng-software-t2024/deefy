package br.com.deefy.model;

import jakarta.persistence.*;

@Entity 
@Table(name = "playlist_compartilhada")
public class PlaylistShare {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private User usuario;

    @Column(name = "permissao", nullable = false, length = 30)
    private String permissao;

    @Column(name = "origem", length = 20)
    private String origem;
    
    @Column(name = "ativo", nullable = false)
    private Boolean ativo;

    protected PlaylistShare() {
    }

    public PlaylistShare(Playlist playlist, User usuario, String permissao, String origem) {
        this.playlist = playlist;
        this.usuario = usuario;
        this.permissao = permissao;
        this.origem = origem;
        this.ativo = true;
    }

    public Long getId() {
        return id;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public User getUsuario() {
        return usuario;
    }

    public String getPermissao() {
        return permissao;
    }

    public void setPermissao(String permissao) {
        this.permissao = permissao;
    }

    public void setOrigem(String origem) {
        this.origem = origem;
    }

    public String getOrigem() {
        return origem;
    }

    public Boolean getAtivo() {
        return ativo;
    }

    public void desativar() {
        this.ativo = false;
    }

    public void ativar() {
        this.ativo = true;
    }
} 
