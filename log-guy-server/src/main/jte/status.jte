@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Status", content = @`
<main id="status" class="admin-page"></main>
<script type="module" src="/static/js/status.js"></script>
`)
