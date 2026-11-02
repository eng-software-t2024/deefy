import { useNavigate } from "react-router-dom";
import { IoChevronBack } from "react-icons/io5";
import logo from "../assets/logo.svg";
import background from "../assets/background.jpg";
import versionNotesData from "../data/versionNotes.json";
import "./VersionNotes.css";

function VersionNotes() {
  const navigate = useNavigate();

  const handleBack = () => {
    if (window.history.length > 1) {
      navigate(-1);
    } else {
      navigate("/home");
    }
  };

  return (
    <div
      className="version-notes-page"
      style={{ backgroundImage: `url(${background})` }}
    >
      <div className="version-notes-overlay"></div>

      <section className="version-notes-wrapper">
        <div className="version-notes-logo">
          <img src={logo} alt="Logo Deefy" />
        </div>

        <h1 className="version-notes-main-title">Notas de lançamento</h1>

        <div className="version-notes-card">
          {versionNotesData.map((note, index) => (
            <div key={note.id || index} className="version-notes-section">
              <div className="version-notes-header">
                <h2>
                  <strong>{note.title}</strong>
                </h2>
                {note.version && (
                  <span className="version-notes-badge">v{note.version}</span>
                )}
              </div>
              <p>{note.content}</p>
            </div>
          ))}
        </div>

        <button
          type="button"
          className="version-notes-back-btn"
          onClick={handleBack}
        >
          <IoChevronBack />
          <span>Voltar</span>
        </button>
      </section>
    </div>
  );
}

export default VersionNotes;
