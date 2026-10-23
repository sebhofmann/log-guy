@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Archivsuche", content = @`
<main class="admin-page">
  <div id="archive"></div>
  <section id="reindex"></section>
</main>
<script type="module" src="/static/js/archive.js"></script>
<script type="module" src="/static/js/reindex.js"></script>
`)
