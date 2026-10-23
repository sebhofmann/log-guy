@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Skripte", content = @`
<main id="scripts" class="scripts-layout" data-admin="${page.user().isAdmin()}"></main>
<script type="module" src="/static/js/scripts.js"></script>
`)
