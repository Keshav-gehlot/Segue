"""Reorder \\bibitem entries by order of first \\cite in the body (IEEE numbering rule)."""
import re, sys
p = sys.argv[1] if len(sys.argv) > 1 else "autoplay.tex"
s = open(p).read()
head, rest = s.split("\\begin{thebibliography}{99}", 1)
bib, tail = rest.split("\\end{thebibliography}", 1)
order = []
for grp in re.findall(r"\\cite\{([^}]*)\}", head):
    for k in grp.split(","):
        k = k.strip()
        if k not in order: order.append(k)
items = re.split(r"(?=\\bibitem\{)", bib)
pre, items = items[0], items[1:]
by = {re.match(r"\\bibitem\{([^}]*)\}", i).group(1): i.rstrip() + "\n\n" for i in items}
missing = [k for k in order if k not in by]; unused = [k for k in by if k not in order]
if missing or unused: sys.exit(f"missing={missing} unused={unused}")
open(p, "w").write(head + "\\begin{thebibliography}{99}" + pre + "".join(by[k] for k in order) + "\\end{thebibliography}" + tail)
print("ordered", len(order), "references")
