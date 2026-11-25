// Police jurisdiction is resolved from the same local polygons drawn on the map.
type Point = [number, number];
type Area = { unit: number; rings: Point[][]; bounds: [number, number, number, number] };
type FeatureCollection = { features?: { properties?: { name?: string }; geometry?: { type?: string; coordinates?: unknown } }[] };

export function parsePoliceAreas(raw: FeatureCollection): Area[] {
  const areas: Area[] = [];
  for (const feature of raw.features ?? []) {
    const unit = Number(feature.properties?.name?.match(/^(\d{1,2})ª\s+CICOM\b/i)?.[1]);
    if (!Number.isInteger(unit) || unit < 1 || unit > 30) continue;
    const geometry = feature.geometry;
    if (geometry?.type !== 'Polygon' || !Array.isArray(geometry.coordinates)) continue;
    const rings = geometry.coordinates as Point[][];
    if (!rings.length || !rings.every(r => Array.isArray(r) && r.length >= 3 &&
      r.every(p => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])))) continue;
    const bounds: Area['bounds'] = [Infinity, Infinity, -Infinity, -Infinity];
    for (const ring of rings) for (const [x,y] of ring) {
      bounds[0] = Math.min(bounds[0],x); bounds[1] = Math.min(bounds[1],y);
      bounds[2] = Math.max(bounds[2],x); bounds[3] = Math.max(bounds[3],y);
    }
    areas.push({unit,rings,bounds});
  }
  return areas;
}

function inside(ring: Point[], x: number, y: number): boolean {
  let result = false;
  for (let i=0,j=ring.length-1;i<ring.length;j=i++) {
    const a=ring[i],b=ring[j];
    if ((a[1]>y)!==(b[1]>y) && x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]) result=!result;
  }
  return result;
}

export function resolvePoliceAreas(areas: Area[], lat: number, lon: number, accuracy: number): string {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || !Number.isFinite(accuracy) || accuracy<=0 || accuracy>35) return '';
  const radius=Math.max(4,accuracy), sy=111195, sx=sy*Math.max(0.15,Math.cos(lat*Math.PI/180));
  const units=new Set<number>();
  let confirmed=false;
  for (const area of areas) {
    const [w,s,e,n]=area.bounds;
    if (lon<w-radius/sx || lon>e+radius/sx || lat<s-radius/sy || lat>n+radius/sy) continue;
    const contains=inside(area.rings[0],lon,lat) && !area.rings.slice(1).some(r=>inside(r,lon,lat));
    let distanceSquared=Infinity;
    for (const ring of area.rings) for(let i=0;i<ring.length;i++){
      const a=ring[i],b=ring[(i+1)%ring.length];
      const ax=(a[0]-lon)*sx,ay=(a[1]-lat)*sy,vx=(b[0]-a[0])*sx,vy=(b[1]-a[1])*sy;
      const len=vx*vx+vy*vy, t=len>0?Math.max(0,Math.min(1,-(ax*vx+ay*vy)/len)):0;
      distanceSquared=Math.min(distanceSquared,(ax+t*vx)**2+(ay+t*vy)**2);
    }
    if(contains || distanceSquared<=radius*radius) units.add(area.unit);
    if(contains && distanceSquared>radius*radius) confirmed=true;
  }
  if (units.size!==1 || !confirmed) return '';
  const unit=Array.from(units)[0];
  return `${unit}º DIP · ${unit}ª CICOM`;
}

let cached: Promise<Area[]> | undefined;
export async function resolvePoliceAt(lat: number, lon: number, accuracy: number): Promise<string> {
  if (!cached) cached=fetch('/cicoms_manaus.geojson').then(response=>{
    if(!response.ok) throw new Error('Mapa territorial indisponível');
    return response.json();
  }).then(parsePoliceAreas).catch(error=>{ cached=undefined; throw error; });
  try { return resolvePoliceAreas(await cached,lat,lon,accuracy); }
  catch { return ''; }
}
