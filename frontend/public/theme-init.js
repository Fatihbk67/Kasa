// Runs before the page draws, so a chosen dark theme never flashes light first.
// A separate file rather than an inline script, because the content security policy allows only same-origin scripts.
try {
  var theme = localStorage.getItem('kasa.theme');
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.setAttribute('data-theme', theme);
  }
} catch (e) {
  // Storage can be blocked; the system theme is then used.
}
