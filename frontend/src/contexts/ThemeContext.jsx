/* eslint-disable react-refresh/only-export-components */
import { createContext, useCallback, useContext, useEffect, useState } from 'react'

export const THEME_STORAGE_KEY = 'deefy-theme'
const ThemeContext = createContext(null)

function getInitialTheme() {
    try {
        const saved = localStorage.getItem(THEME_STORAGE_KEY)
        if (saved === 'light' || saved === 'dark') return saved
    } catch { /* localStorage indisponível */ }
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

export function ThemeProvider({ children }) {
    const [theme, setTheme] = useState(getInitialTheme)

    useEffect(() => {
        document.documentElement.setAttribute('data-theme', theme)
        try { localStorage.setItem(THEME_STORAGE_KEY, theme) } catch { /* ignora */ }
    }, [theme])

    const toggleTheme = useCallback(
        () => setTheme((t) => (t === 'dark' ? 'light' : 'dark')),
        []
    )

    return (
        <ThemeContext.Provider value={{ theme, setTheme, toggleTheme }}>
            {children}
        </ThemeContext.Provider>
    )
}

export function useTheme() {
    const ctx = useContext(ThemeContext)
    if (!ctx) throw new Error('useTheme deve ser usado dentro de <ThemeProvider>')
    return ctx
}