import { useNavigate } from "react-router-dom";
import { IoChevronBack } from "react-icons/io5";
import logo from "../assets/logo.svg";
import background from "../assets/background.jpg";
import versionNotesData from "../data/versionNotes.json";
import "./VersionNotes.css";

function VersionNotes() {
  const navigate = useNavigate();

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
              <h2>
                <strong>{note.title}</strong>
              </h2>
              <p>{note.content}</p>
            </div>
          ))}
        </div>

        <button
          type="button"
          className="version-notes-back-btn"
          onClick={() => navigate(-1)}
        >
          <IoChevronBack />
          <span>Voltar</span>
        </button>
      </section>
    </div>
  );
}

export default VersionNotes;
