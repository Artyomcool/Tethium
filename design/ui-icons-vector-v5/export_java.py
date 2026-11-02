"""Compile reviewed SVG geometry to native Java2D, preserving vector HiDPI rendering."""
from pathlib import Path
import re,xml.etree.ElementTree as ET,shutil
from svgpathtools import parse_path,Line,CubicBezier,QuadraticBezier,Arc
p=Path(__file__).parent;root=p.parents[1]
out=['package dev.tethium.desktop;','import java.awt.*;','import java.awt.geom.*;','/** Generated from design/ui-icons-vector-v5/export_java.py. */','final class VectorIconData {','static void paint(String name, Graphics2D g) {','switch(name) {']
def n(v):return f'{v:.6f}'
for f in sorted(p.glob('*.svg')):
 out.append('case "'+f.stem+'" -> {')
 tree=ET.parse(f).getroot();outer=list(tree)[0]
 tx,ty,scale=map(float,re.findall(r'-?\d+(?:\.\d+)?',outer.attrib['transform']))
 out.append(f'g.translate({n(tx)},{n(ty)});g.scale({n(scale)},{n(scale)});')
 for group in outer:
  out.append(f'g.setStroke(new BasicStroke({group.attrib["stroke-width"]}f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));')
  for el in group:
   tag=el.tag.split('}')[-1];a=el.attrib
   if tag=='rect':
    vals=[float(a[k]) for k in ['x','y','width','height']];radius=float(a.get('rx',0))*2
    out.append('g.draw(new RoundRectangle2D.Double('+','.join(n(v) for v in vals+[radius,radius])+'));')
   elif tag=='circle':
    x,y,r=[float(a[k]) for k in ['cx','cy','r']];out.append(f'g.draw(new Ellipse2D.Double({n(x-r)},{n(y-r)},{n(2*r)},{n(2*r)}));')
   else:
    out.append('{var p=new Path2D.Double();')
    for sub in parse_path(a['d']).continuous_subpaths():
     out.append(f'p.moveTo({n(sub.start.real)},{n(sub.start.imag)});')
     for seg in sub:
      segments=list(seg.as_cubic_curves(4)) if isinstance(seg,Arc) else [seg]
      for s in segments:
       if isinstance(s,Line):out.append(f'p.lineTo({n(s.end.real)},{n(s.end.imag)});')
       elif isinstance(s,QuadraticBezier):out.append('p.quadTo('+','.join(n(v) for z in [s.control,s.end] for v in [z.real,z.imag])+');')
       else:out.append('p.curveTo('+','.join(n(v) for z in [s.control1,s.control2,s.end] for v in [z.real,z.imag])+');')
     if sub.isclosed():out.append('p.closePath();')
    out.append('g.draw(p);}')
 out.append('}')
out+=['default -> throw new IllegalArgumentException("Unknown icon: "+name);','}','}','}']
(root/'src/main/java/dev/tethium/desktop/VectorIconData.java').write_text('\n'.join(out),encoding='utf-8')
resources=root/'src/main/resources/dev/tethium/desktop/icons/v5';resources.mkdir(parents=True,exist_ok=True)
for f in p.glob('*.svg'):shutil.copy2(f,resources/f.name)
print('Exported 32 native vector icons and SVG sources')
