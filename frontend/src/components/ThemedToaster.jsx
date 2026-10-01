import { Toaster } from 'sonner'
import { useTheme } from '../contexts/ThemeContext'

export default function ThemedToaster() {
    const { theme } = useTheme()
    return (
        <Toaster
            position="top-right"
            closeButton
            theme={theme}
            toastOptions={{
                style: {
                    background: 'var(--surface-solid)',
                    color: 'var(--text-primary)',
                    border: '1px solid var(--border-subtle)',
                    borderRadius: '14px',
                    fontFamily: "'Inter', sans-serif",
                    fontSize: '0.88rem',
                    fontWeight: '500',
                    boxShadow: 'var(--shadow-lg)',
                    backdropFilter: 'blur(12px)',
                },
            }}
        />
    )
}