import React from 'react';
import packageInfo from '../../package.json';
import { FaGithub } from 'react-icons/fa';
import './VersionFooter.css';

export default function VersionFooter() {
  const projectName = packageInfo.name === 'client' ? 'Deefy' : (packageInfo.name || 'Deefy');

  return (
    <footer
      className="version-footer"
      role="contentinfo"
      aria-label="Rodapé de versão"
    >
      <div className="version-footer-left">
        <p>
          © {new Date().getFullYear()} <strong className="version-footer-brand">{projectName}</strong>
        </p>
      </div>

      <div className="version-footer-right">
        <a
          target="_blank"
          rel="noopener noreferrer"
          className="version-footer-badge"
          title={`Ver notas de lançamento para v${packageInfo.version}`}
        >
          <span>{packageInfo.version}</span>
        </a>
      </div>
    </footer>
  );
}
