// Während eines Scans die Seite alle 3 Sekunden neu laden
if (document.querySelector('.scan-running')) {
  setTimeout(() => window.location.reload(), 3000);
}

// Klick auf eine Tabellenzeile öffnet die Detailansicht in einem neuen Fenster
document.querySelectorAll('tr.project-row').forEach(row => {
  row.addEventListener('click', event => {
    if (event.target.closest('a')) {
      return; // Links (target="_blank") selbst behandeln lassen
    }
    window.open(row.dataset.href, '_blank', 'noopener');
  });
});
