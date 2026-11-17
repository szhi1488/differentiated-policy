const H = { x: 462, y: 358 };
const B = { x: 492, y: 470 };
const L1 = 70;
const L2 = 66;
const rc = 18;
const N = 16;

const r2 = (v) => Math.round(v * 10) / 10;

function legPath(deg) {
  const th = (deg * Math.PI) / 180;
  const P = { x: B.x + rc * Math.cos(th), y: B.y + rc * Math.sin(th) };
  const dx = P.x - H.x;
  const dy = P.y - H.y;
  const d = Math.hypot(dx, dy);
  const a = (L1 * L1 - L2 * L2 + d * d) / (2 * d);
  const h = Math.sqrt(Math.max(L1 * L1 - a * a, 0));
  const ux = dx / d;
  const uy = dy / d;
  const nx = uy;
  const ny = -ux;
  const K = { x: H.x + a * ux + h * nx, y: H.y + a * uy + h * ny };
  return `M${H.x} ${H.y}L${r2(K.x)} ${r2(K.y)}L${r2(P.x)} ${r2(P.y)}`;
}

const right = [];
const left = [];
for (let i = 0; i <= N; i++) {
  right.push(legPath(-90 + (i * 360) / N));
  left.push(legPath(-90 + 180 + (i * 360) / N));
}
console.log('RIGHT=' + right.join(';'));
console.log('LEFT=' + left.join(';'));
