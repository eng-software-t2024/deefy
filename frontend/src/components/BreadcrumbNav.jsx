import { Link } from "react-router-dom";
import { MdChevronRight } from "react-icons/md";
import "./BreadcrumbNav.css";

/**
 * Componente reutilizável de caminhos de navegação (Breadcrumbs).
 * Apresenta a hierarquia das páginas e permite ao usuário se localizar e retornar às seções pai.
 *
 * @param {{
 *   items: Array<{ label: string, to?: string }>,
 *   className?: string
 * }} props
 */
function BreadcrumbNav({ items = [], className = "" }) {
  if (!items || items.length === 0) return null;

  return (
    <nav
      className={`deefy-breadcrumb-nav ${className}`.trim()}
      aria-label="Caminho de navegação"
    >
      <ol className="deefy-breadcrumb-list">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          const key = item.to || `${item.label}-${index}`;

          return (
            <li
              key={key}
              className={`deefy-breadcrumb-item${isLast ? " is-current" : ""}`}
            >
              {index > 0 && (
                <MdChevronRight
                  className="deefy-breadcrumb-separator"
                  aria-hidden="true"
                />
              )}

              {item.to && !isLast ? (
                <Link to={item.to} className="deefy-breadcrumb-link">
                  {item.label}
                </Link>
              ) : (
                <span
                  className="deefy-breadcrumb-current"
                  aria-current={isLast ? "page" : undefined}
                  title={item.label}
                >
                  {item.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export default BreadcrumbNav;
