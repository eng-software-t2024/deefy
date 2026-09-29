import React, { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import packageInfo from '../../package.json';
import { FaGithub } from 'react-icons/fa';
import './VersionFooter.css';

// Rotas públicas ou de formulário que não utilizam a sidebar de navegação
const ROUTES_WITHOUT_SIDEBAR = [
  '/',
  '/login',
  '/registration',
  '/forgot-password',
  '/redefinepass',
  '/verify-account',
  '/configuration',
  '/edit-profile',
  '/custom-profile',
];

export default function VersionFooter() {
  const location = useLocation();
  const [hasSidebar, setHasSidebar] = useState(() => Boolean(document.querySelector('.sidebar')));

  useEffect(() => {
    const checkDom = () => {
      setHasSidebar(Boolean(document.querySelector('.sidebar')));
    };

    checkDom();
    const timer1 = setTimeout(checkDom, 50);
    const timer2 = setTimeout(checkDom, 300);

    return () => {
      clearTimeout(timer1);
      clearTimeout(timer2);
    };
  }, [location.pathname]);

  const projectName = packageInfo.name === 'client' ? 'Deefy' : (packageInfo.name || 'Deefy');

  return (
    <footer
      className={`version-footer ${hasSidebar ? 'has-sidebar' : ''}`}
      role="contentinfo"
      aria-label="Rodapé de versão"
    >
      <div className="version-footer-left">
        <p>
          © {new Date().getFullYear()} <strong className="version-footer-brand">{projectName}</strong>.
        </p>
      </div>

      <div className="version-footer-right">
        <a
          href="https://github.com/eng-software-t2024/deefy/releases"
          target="_blank"
          rel="noopener noreferrer"
          className="version-footer-badge"
          title={`Ver notas de lançamento para v${packageInfo.version}`}
        >
          <FaGithub className="release-icon" />
          <span>v{packageInfo.version}</span>
        </a>
      </div>
    </footer>
  );
}
