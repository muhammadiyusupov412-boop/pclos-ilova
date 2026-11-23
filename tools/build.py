import sys,re,pathlib
src=pathlib.Path(sys.argv[1]).read_text(); lib=pathlib.Path(sys.argv[2]); out=pathlib.Path(sys.argv[3])
cj=(lib/'chart.js/dist/chart.umd.js').read_text(); xl=(lib/'xlsx/dist/xlsx.mini.min.js').read_text()
src=src.replace('<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"></script>','<script>'+cj.replace('</script','<\\/script')+'</script>')
src=src.replace('<script src="https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.mini.min.js"></script>','<script>'+xl.replace('</script','<\\/script')+'</script>')
head='''<!doctype html>
<html lang="uz"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#2B4BF2"><link rel="manifest" href="manifest.webmanifest">
<link rel="icon" href="icon-192.png"><link rel="apple-touch-icon" href="icon-192.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0}[hidden]{display:none!important}</style>
'''
lf=(lib/'leaflet/dist/leaflet.js').read_text()
src=src.replace('<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>','<script>'+lf.replace('</script','<\\/script')+'</script>')
out.write_text(head+src.replace('<div class="wrap" id="app">','</head><body><div class="wrap" id="app">',1)+'\n</body></html>\n')
