package net.tenyls.kinotv

import android.net.Uri
import org.json.JSONObject

/**
 * Блокировка рекламы.
 * Встроенный список + список из remote/config.json (подтягивается сам, без переустановки).
 */
object AdBlock {

    private val builtInHosts = listOf(
        // Google / общие сети
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "imasdk.googleapis.com", "pagead2.googlesyndication.com",
        // Яндекс-реклама и счётчики
        "an.yandex.ru", "yabs.yandex.ru", "mc.yandex.ru", "adfox.ru", "adfox.yandex.ru",
        // Российские сети
        "adriver.ru", "betweendigital.com", "marketgid.com", "mgid.com", "adskeeper.co.uk", "adskeeper.com",
        "smi2.ru", "smi2.net", "24smi.org", "24smi.net", "lentainform.com", "relap.io", "kadam.net",
        "directadvert.ru", "ad.mail.ru", "rs.mail.ru", "top-fwz1.mail.ru", "acint.net", "tizerpro.com",
        "teasernet.com", "bodyclick.net", "admitad.com", "buzzoola.com", "otm-r.com", "sape.ru",
        "tns-counter.ru", "gemius.pl", "liveinternet.ru", "hit.ua", "cityads.com", "tizerbox.com",
        "videonow.ru", "moevideo.biz", "moevideo.net", "videoroll.net", "vidroll.ru", "adwile.com",
        "ad.vidverto.io", "vidverto.io", "adlook.me", "begun.ru", "a-ads.com", "nativeroll.tv",
        // Поп-апы, пуши, казино-редиректы
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "onclkads.com", "adsterra.com",
        "exoclick.com", "exdynsrv.com", "magsrv.com", "juicyads.com", "trafficjunky.net", "hilltopads.net",
        "clickadu.com", "adcash.com", "bidvertiser.com", "tsyndicate.com", "clickaine.com", "realsrv.com",
        "realpush.digital", "pushwhy.com", "push.express", "onesignal.com", "sendpulse.com", "pushame.com",
        "richpush.co", "evadav.com", "galaksion.com", "adspyglass.com", "ad-maven.com", "admaven.com",
        "monetag.com", "highperformanceformat.com", "profitabledisplaynetwork.com", "displayvertising.com",
        "rollerads.com", "pushprofit.net", "zeropark.com", "trafficstars.com", "adxadserv.com",
        "1xbet.com", "1xstavka.ru", "pin-up.com", "vulkan.com", "joycasino.com", "melbet.com"
    )

    private val builtInCss = """
        .adsbygoogle, ins.adsbygoogle, [id^="yandex_rtb"], [id*="adfox"], [class*="adfox"],
        [id^="div-gpt-ad"], [id^="ad_"], [id^="ads_"], [class^="ad-"], [class*=" ad-"],
        [class*="advert"], [id*="advert"], [class*="reklama"], [id*="reklama"],
        [class*="brending"], [class*="branding"], [id*="branding"],
        [class*="popunder"], [class*="push-notif"], iframe[src*="ads"], iframe[width="728"],
        iframe[width="300"][height="250"], a[href*="1xbet"], a[href*="casino"], a[href*="kazino"]
        { display: none !important; visibility: hidden !important; }
        body { background-image: none !important; }
    """.trimIndent()

    @Volatile private var hosts: Set<String> = builtInHosts.toSet()
    @Volatile var extraCss: String = ""
        private set
    @Volatile var allowNav: Set<String> = emptySet()
        private set

    val css: String get() = builtInCss + "\n" + extraCss

    /** Применить конфиг из remote/config.json */
    fun load(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            val set = HashSet(builtInHosts)
            o.optJSONArray("blockHosts")?.let { a -> for (i in 0 until a.length()) set.add(a.getString(i).trim().lowercase()) }
            hosts = set
            extraCss = o.optJSONArray("hideCss")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } } ?: ""
            val allow = HashSet<String>()
            o.optJSONArray("allowNavHosts")?.let { a -> for (i in 0 until a.length()) allow.add(a.getString(i).trim().lowercase()) }
            allowNav = allow
        } catch (_: Exception) {
        }
    }

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (hosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    /** Основной домен: "www.site.net" -> "site.net" */
    fun baseDomain(host: String?): String {
        if (host.isNullOrEmpty()) return ""
        val parts = host.lowercase().split('.')
        if (parts.size <= 2) return host.lowercase()
        val twoLevelZones = setOf("co.uk", "com.ua", "org.ua", "com.ru", "net.ru", "org.ru", "com.tr", "com.br")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in twoLevelZones) parts.takeLast(3).joinToString(".") else lastTwo
    }

    fun sameSite(url: Uri, home: String): Boolean {
        val h = url.host?.lowercase() ?: return false
        if (home.isEmpty()) return true
        if (h == home || h.endsWith(".$home")) return true
        return allowNav.any { h == it || h.endsWith(".$it") }
    }

    /** Скрипт, который чистит страницу: стили, window.open, прозрачные слои-ловушки. */
    fun pageScript(home: String): String {
        val cssJs = JSONObject.quote(css)
        val homeJs = JSONObject.quote(home)
        return """
(function(){
  if (window.__kinoClean) return; window.__kinoClean = true;
  var HOME = $homeJs;
  try { window.open = function(){ return null; }; } catch(e) {}
  function addCss(){
    if (document.getElementById('__kino_css')) return;
    var s = document.createElement('style'); s.id='__kino_css'; s.textContent = $cssJs;
    (document.head || document.documentElement).appendChild(s);
  }
  function ext(href){
    try { var u = new URL(href, location.href); var h = u.hostname;
      return !(h === HOME || h.endsWith('.' + HOME)); } catch(e) { return false; }
  }
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a && a.href && ext(a.href) && (a.target === '_blank' || /casino|kazino|1xbet|bet|vulkan|azino/i.test(a.href))) {
      e.preventDefault(); e.stopImmediatePropagation();
    }
  }, true);
  function sweep(){
    addCss();
    if (!document.body) return;
    var vw = innerWidth, vh = innerHeight, kids = document.body.children;
    for (var i = kids.length - 1; i >= 0; i--) {
      var el = kids[i];
      if (el.querySelector && el.querySelector('video')) continue;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      var z = parseInt(cs.zIndex) || 0; if (z < 999) continue;
      var r = el.getBoundingClientRect();
      var big = r.width > vw * 0.8 && r.height > vh * 0.8;
      var invisible = parseFloat(cs.opacity) < 0.15 || (cs.backgroundColor === 'rgba(0, 0, 0, 0)' && !el.innerText.trim());
      var extLink = (el.tagName === 'A' && ext(el.href)) || (el.querySelector && Array.prototype.some.call(el.querySelectorAll('a[href]'), function(a){ return ext(a.href); }));
      var extFrame = el.querySelector && Array.prototype.some.call(el.querySelectorAll('iframe[src]'), function(f){ return ext(f.src) && f.getBoundingClientRect().width < vw * 0.6; });
      if ((big && invisible) || (big && extLink && !el.querySelector('iframe')) || (extFrame && !big)) el.remove();
    }
  }
  sweep();
  setInterval(sweep, 1500);
})();
""".trimIndent()
    }
}
