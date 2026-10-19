#!/usr/bin/env python3
"""
Build the iOS app's content-blocking rules from EasyList and EasyPrivacy.

iOS allows no engine but WebKit, so uBlock Origin cannot run there. What
WebKit offers instead is a content rule list: JSON rules compiled into a
bytecode the engine applies itself. This converts the parts of the Adblock
Plus filter syntax that map onto it cleanly:

  ||host^ and ||host/path        -> url-filter, anchored to the host
  $third-party, $script, $image, $stylesheet, $xmlhttprequest, $subdocument,
  $font, $media, $websocket, $ping, $popup, $domain=...
  @@ exceptions                  -> ignore-previous-rules, after all blocks
  ##selector (generic)           -> css-display-none on every page
  domain.com##selector           -> css-display-none with if-domain

and drops what does not: regular-expression filters, $csp, $redirect,
$removeparam and other rewrites, procedural cosmetics (:has-text, :-abp-,
:xpath, …), and #@# cosmetic exceptions. That is most of uBlock's reach for
ordinary ads and trackers, and none of its scriptlets — which is the honest
difference between this and the real thing.

Usage: tools/build-blocklist.py [out.json]       (downloads the lists)
       tools/build-blocklist.py out.json a.txt b.txt   (uses local copies)
"""
import json
import re
import sys
import urllib.request

LISTS = [
    "https://easylist.to/easylist/easylist.txt",
    "https://easylist.to/easylist/easyprivacy.txt",
]

# WebKit's ceiling is 150,000 rules per list; stay well clear so a list that
# grows upstream does not suddenly fail to compile on the phone.
MAX_RULES = 140_000

TYPES = {
    "script": "script",
    "image": "image",
    "stylesheet": "style-sheet",
    "xmlhttprequest": "fetch",
    "subdocument": "document",
    "font": "font",
    "media": "media",
    "websocket": "websocket",
    "ping": "ping",
    "popup": "popup",
    "other": "other",
}
IGNORED_OPTIONS = {"match-case", "1p", "first-party", "~third-party", "all", "document", "doc"}
UNSUPPORTED = re.compile(
    r"^(csp|redirect|redirect-rule|removeparam|rewrite|replace|header|"
    r"permissions|urltransform|elemhide|generichide|genericblock|specifichide|"
    r"badfilter|important|empty|mp4|sitekey|webrtc|object|object-subrequest|"
    r"inline-script|inline-font|to|from|method|denyallow|cname|uritransform)\b"
)
PROCEDURAL = re.compile(
    r":(-abp-|has-text|contains|xpath|upward|remove|style|matches-css|"
    r"matches-attr|matches-path|matches-media|min-text-length|watch-attr|"
    r"others|nth-ancestor|if|if-not|properties|shadow)"
)


def escape(text):
    return re.sub(r"([.+?^${}()|\[\]\\])", r"\\\1", text)


def pattern_to_regex(pattern):
    """An ABP network pattern to the regex subset WebKit accepts."""
    out = ""
    if pattern.startswith("||"):
        out = r"^[^:]+://+([^:/]+\.)?"
        pattern = pattern[2:]
    elif pattern.startswith("|"):
        out = "^"
        pattern = pattern[1:]
    end = ""
    if pattern.endswith("|"):
        end = "$"
        pattern = pattern[:-1]
    for ch in pattern:
        if ch == "*":
            out += ".*"
        elif ch == "^":
            out += "[/:?=&]"
        else:
            out += escape(ch)
    return out + end


def network_rule(line):
    exception = line.startswith("@@")
    if exception:
        line = line[2:]
    options = []
    if "$" in line:
        line, _, opts = line.rpartition("$")
        options = [o.strip() for o in opts.split(",") if o.strip()]
    if not line or line.startswith("/") and line.endswith("/"):
        return None  # regex filters
    if len(line) < 4 and not line.startswith("||"):
        return None
    if not line.isascii():
        return None  # WebKit's url-filter is ASCII only

    trigger = {"url-filter": pattern_to_regex(line), "url-filter-is-case-sensitive": False}
    load_types = []
    resource_types = []
    if_domain = []
    unless_domain = []
    for opt in options:
        name, _, value = opt.partition("=")
        negated = name.startswith("~")
        bare = name.lstrip("~")
        if bare in IGNORED_OPTIONS:
            continue
        if UNSUPPORTED.match(bare):
            return None
        if bare in ("third-party", "3p"):
            load_types.append("first-party" if negated else "third-party")
        elif bare == "domain":
            for d in value.split("|"):
                if not d or "*" in d or "/" in d:
                    continue
                (unless_domain if d.startswith("~") else if_domain).append("*" + d.lstrip("~").lower())
        elif bare in TYPES and not negated:
            resource_types.append(TYPES[bare])
        elif bare in TYPES and negated:
            continue  # "everything but X" has no WebKit form; keep the rule broad
        else:
            return None
    if if_domain and unless_domain:
        unless_domain = []  # WebKit takes one or the other
    if load_types:
        trigger["load-type"] = sorted(set(load_types))
    if resource_types:
        trigger["resource-type"] = sorted(set(resource_types))
    if if_domain:
        trigger["if-domain"] = sorted(set(if_domain))
    if unless_domain:
        trigger["unless-domain"] = sorted(set(unless_domain))
    try:
        re.compile(trigger["url-filter"])
    except re.error:
        return None
    action = {"type": "ignore-previous-rules" if exception else "block"}
    return exception, {"trigger": trigger, "action": action}


def cosmetic(line):
    """(domains or None, selector) for a plain element-hiding rule, or None."""
    if "#@#" in line or "#?#" in line or "#$#" in line or "#%#" in line or "#+js" in line:
        return None
    domains, _, selector = line.partition("##")
    selector = selector.strip()
    if not selector or PROCEDURAL.search(selector) or selector.startswith("+js"):
        return None
    if "{" in selector or "}" in selector or "\\" in selector:
        return None
    doms = [d.strip().lower() for d in domains.split(",") if d.strip()] if domains else []
    if any("*" in d or d.startswith("~") or "/" in d for d in doms):
        return None
    return (doms or None), selector


def convert(texts):
    blocks, exceptions = [], []
    generic, specific = [], {}
    seen = set()
    for text in texts:
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("!") or line.startswith("["):
                continue
            if "##" in line or "#@#" in line or "#?#" in line or "#$#" in line:
                hit = cosmetic(line)
                if not hit:
                    continue
                doms, selector = hit
                if doms is None:
                    if selector not in seen:
                        seen.add(selector)
                        generic.append(selector)
                else:
                    specific.setdefault(tuple(sorted(doms)), []).append(selector)
                continue
            got = network_rule(line)
            if not got:
                continue
            exception, rule = got
            key = json.dumps(rule, sort_keys=True)
            if key in seen:
                continue
            seen.add(key)
            (exceptions if exception else blocks).append(rule)

    rules = []
    # Generic hiding in small batches: one selector WebKit cannot parse
    # spoils only its own batch, not the whole list.
    for i in range(0, len(generic), 20):
        rules.append({
            "trigger": {"url-filter": ".*"},
            "action": {"type": "css-display-none", "selector": ", ".join(generic[i:i + 20])},
        })
    for doms, selectors in specific.items():
        for i in range(0, len(selectors), 20):
            rules.append({
                "trigger": {"url-filter": ".*", "if-domain": ["*" + d for d in doms]},
                "action": {"type": "css-display-none", "selector": ", ".join(selectors[i:i + 20])},
            })
    rules.extend(blocks)
    # Exceptions only cancel what came before them, so they go last.
    rules.extend(exceptions)
    if len(rules) > MAX_RULES:
        # Cosmetics first, so trim from the front's generic batches last:
        # network blocking is what matters for privacy.
        rules = rules[-MAX_RULES:]
    return rules


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "ios/Bork/Resources/blocklist.json"
    if len(sys.argv) > 2:
        texts = [open(p, encoding="utf-8").read() for p in sys.argv[2:]]
    else:
        texts = []
        for url in LISTS:
            req = urllib.request.Request(url, headers={"User-Agent": "bork-mobile build"})
            with urllib.request.urlopen(req, timeout=60) as r:
                texts.append(r.read().decode("utf-8", "replace"))
    rules = convert(texts)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rules, f, separators=(",", ":"))
    kinds = {}
    for r in rules:
        kinds[r["action"]["type"]] = kinds.get(r["action"]["type"], 0) + 1
    print(f"{len(rules)} rules -> {out}  {kinds}")


if __name__ == "__main__":
    main()
