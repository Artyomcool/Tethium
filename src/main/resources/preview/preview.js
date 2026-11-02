(async () => {
  try {
    const image = document.querySelector('#sample');
    await image.decode();
    const data = await (await fetch('data.json')).json();
    let clicks = 0;
    const button = document.querySelector('#increment');
    button.onclick = () => button.textContent = `Interactions: ${++clicks}`;
    button.click();
    const styled = getComputedStyle(document.documentElement).backgroundColor === 'rgb(17, 24, 32)';
    if (!styled || !image.naturalWidth || clicks !== 1 || data.marker !== 'tethium-preview') throw Error('Resource check failed');
    document.querySelector('#status').textContent = 'All preview checks passed.';
    document.querySelector('#result').textContent = 'Rendered by embedded Chromium';
    requestAnimationFrame(() => requestAnimationFrame(() => console.log('TETHIUM_READY:' + JSON.stringify({javascript:true,css:styled,image:image.naturalWidth,fetch:data.marker,clicks}))));
  } catch (e) { console.error('TETHIUM_ERROR:' + e.message); }
})();

