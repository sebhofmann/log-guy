@import de.gbv.logguy.server.web.PageContext
@param PageContext page
@template.layout.page(page = page, title = "Suche", content = @`
<link rel="stylesheet" href="/static/vendor/uPlot.min.css">
<main id="search" class="search-page"></main>
<script type="module" src="/static/js/search.js"></script>
`)
