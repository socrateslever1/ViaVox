import { describe, expect, it } from 'vitest';
import { parsePoliceAreas, resolvePoliceAreas } from './policeTerritory';

function sector(unit:number, west:number, east:number) {
  return {properties:{name:`${unit}ª CICOM`}, geometry:{type:'Polygon',coordinates:[[
    [west,-3.14],[east,-3.14],[east,-3.12],[west,-3.12],[west,-3.14]
  ]]}};
}

describe('police sector boundaries',()=>{
  it('distinguishes the units and withholds uncertain boundary fixes',()=>{
    const areas=parsePoliceAreas({features:[sector(7,-60.03,-60.02),sector(29,-60.02,-60.01)]});
    expect(resolvePoliceAreas(areas,-3.13,-60.025,6)).toBe('7º DIP · 7ª CICOM');
    expect(resolvePoliceAreas(areas,-3.13,-60.015,6)).toBe('29º DIP · 29ª CICOM');
    expect(resolvePoliceAreas(areas,-3.13,-60.02001,10)).toBe('');
    expect(resolvePoliceAreas(areas,-3.13,-60.015,40)).toBe('');
  });
  it('never selects the first of two overlapping units',()=>{
    const a=sector(7,-60.03,-60.01),b=sector(29,-60.03,-60.01);
    for(const features of [[a,b],[b,a]]) expect(resolvePoliceAreas(parsePoliceAreas({features}),-3.13,-60.02,6)).toBe('');
  });
});
