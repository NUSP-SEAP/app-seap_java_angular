export function hojeDdMm(): string {
  const d = new Date();
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export function hojeAgendaLabel(): string {
  const d = new Date();
  const weekday = d.toLocaleDateString('pt-BR', { weekday: 'long' })
    .split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join('-');
  return `${hojeDdMm()} (${weekday})`;
}
