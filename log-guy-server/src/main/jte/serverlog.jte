@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Server-Log", content = @`
<main id="serverlog" class="admin-page"></main>
<script type="module" src="/static/js/serverlog.js"></script>
`)
