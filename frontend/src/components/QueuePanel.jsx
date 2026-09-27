import { useRef, useState } from 'react';
import { FiX } from 'react-icons/fi';
import { MdDragHandle } from 'react-icons/md';
import { usePlayer } from '../contexts/PlayerContext';
import './QueuePanel.css';

function getTrackId(track) {
    return String(track?.id ?? track?.musicId ?? track?.uuid ?? '');
}

function getTrackTitle(track) {
    return track?.title || track?.titulo || track?.name || 'Sem título';
}

function getTrackArtist(track) {
    return track?.artist || track?.artista || track?.artistName || '';
}

function getTrackCover(track) {
    return track?.coverUrl || track?.capaUrl || track?.cover || '';
}

export default function QueuePanel({ isOpen, onClose }) {
    const { queue, currentTrack, removeFromQueue, reorderQueue, playTrack } = usePlayer();

    // Estado para controlar o drag and drop
    const [draggingIndex, setDraggingIndex] = useState(null);
    const [dragOverIndex, setDragOverIndex] = useState(null);
    const dragNodeRef = useRef(null);

    function handleDragStart(e, index) {
        setDraggingIndex(index);
        dragNodeRef.current = e.target;
        e.dataTransfer.effectAllowed = 'move';
    }

    function handleDragOver(e, index) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        if (index !== draggingIndex) {
            setDragOverIndex(index);
        }
    }

    function handleDrop(e, toIndex) {
        e.preventDefault();
        if (draggingIndex !== null && draggingIndex !== toIndex) {
            reorderQueue(draggingIndex, toIndex);
        }
        setDraggingIndex(null);
        setDragOverIndex(null);
    }

    function handleDragEnd() {
        setDraggingIndex(null);
        setDragOverIndex(null);
    }

    function handleClearQueue() {
        // Remove todas as músicas que não são a atual
        const currentId = getTrackId(currentTrack);
        queue.forEach((track) => {
            if (getTrackId(track) !== currentId) {
                removeFromQueue(getTrackId(track));
            }
        });
    }

    const currentId = getTrackId(currentTrack);
    const nonCurrentCount = queue.filter(t => getTrackId(t) !== currentId).length;

    return (
        <div className={`queue-panel ${isOpen ? 'open' : ''}`}>
            {/* Cabeçalho */}
            <div className="queue-panel__header">
                <div style={{ display: 'flex', alignItems: 'center' }}>
                    <h2 className="queue-panel__title">Fila de reprodução</h2>
                    <span className="queue-panel__count">{queue.length} música{queue.length !== 1 ? 's' : ''}</span>
                </div>
                <button className="queue-panel__close" onClick={onClose} aria-label="Fechar fila">
                    <FiX size={20} />
                </button>
            </div>

            {/* Lista de músicas */}
            <div className="queue-panel__list">
                {queue.length === 0 ? (
                    <div className="queue-panel__empty">
                        <MdDragHandle size={40} />
                        <p>A fila está vazia</p>
                        <p style={{ fontSize: '0.75rem' }}>Adicione músicas para começar</p>
                    </div>
                ) : (
                    queue.map((track, index) => {
                        const trackId = getTrackId(track);
                        const isActive = trackId === currentId;
                        const isDragging = draggingIndex === index;
                        const isDragOver = dragOverIndex === index;

                        return (
                            <div
                                key={trackId || index}
                                className={`queue-item ${isActive ? 'active' : ''} ${isDragging ? 'dragging' : ''} ${isDragOver ? 'drag-over' : ''}`}
                                draggable
                                onDragStart={(e) => handleDragStart(e, index)}
                                onDragOver={(e) => handleDragOver(e, index)}
                                onDrop={(e) => handleDrop(e, index)}
                                onDragEnd={handleDragEnd}
                                onDoubleClick={() => playTrack(track)}
                            >
                                {/* Handle de arraste */}
                                <div className="queue-item__drag-handle">
                                    <MdDragHandle size={18} />
                                </div>

                                {/* Capa */}
                                <img
                                    className="queue-item__cover"
                                    src={getTrackCover(track) || '/logo.svg'}
                                    alt={getTrackTitle(track)}
                                    onError={(e) => { e.target.src = '/logo.svg'; }}
                                />

                                {/* Título e artista */}
                                <div className="queue-item__info">
                                    <div className="queue-item__title">{getTrackTitle(track)}</div>
                                    <div className="queue-item__artist">{getTrackArtist(track)}</div>
                                </div>

                                {/* Botão remover — não aparece na música atual */}
                                {!isActive && (
                                    <button
                                        className="queue-item__remove"
                                        onClick={() => removeFromQueue(trackId)}
                                        aria-label="Remover da fila"
                                    >
                                        <FiX size={16} />
                                    </button>
                                )}
                            </div>
                        );
                    })
                )}
            </div>

            {/* Rodapé com limpar fila */}
            {nonCurrentCount > 0 && (
                <div className="queue-panel__clear">
                    <button onClick={handleClearQueue}>
                        Limpar fila
                    </button>
                </div>
            )}
        </div>
    );
}