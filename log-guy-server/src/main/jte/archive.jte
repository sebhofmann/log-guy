@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Archivsuche", content = @`
<main id="archive" class="admin-page"></main>
<script type="module" src="/static/js/archive.js"></script>
`)
