@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Meldungen", content = @`
<main class="admin-page">
  <h1>Meldungen</h1>
  <div id="alerts"></div>
  <h2>Regeln</h2>
  <div id="rules"></div>
</main>
<script type="module" src="/static/js/alerts.js"></script>
`)
