import './InlineError.css'

function InlineError({ message, onRetry }) {
  return (
    <div className="inline-error" role="alert">
      <div className="inline-error-icon" aria-hidden="true">
        <svg viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
          <circle cx="32" cy="32" r="30" stroke="rgba(57,240,208,0.15)" strokeWidth="2" />
          <path
            d="M24 44V24l20-4v20"
            stroke="rgba(57,240,208,0.5)"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="20" cy="44" r="4" fill="rgba(57,240,208,0.4)" />
          <circle cx="40" cy="40" r="4" fill="rgba(57,240,208,0.4)" />
          <line x1="20" y1="20" x2="44" y2="44" stroke="#ff5d5d" strokeWidth="2.5" strokeLinecap="round" />
        </svg>
      </div>

      <h3 className="inline-error-heading">Não foi possível carregar</h3>
      <p className="inline-error-body">{message}</p>

      {onRetry && (
        <button type="button" className="inline-error-retry" onClick={onRetry}>
          Tentar novamente
        </button>
      )}
    </div>
  )
}

export default InlineError
