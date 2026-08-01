# Maalstroom Marine webapp

Eerste versie van het dashboard.

## Inhoud

- `index.html` — structuur van de webapp
- `style.css` — vormgeving
- `app.js` — gegevens en schermupdates

## Testen op de Mac

Open `index.html` in Firefox.

Deze versie gebruikt testdata. De Android-app gaat later live NMEA-data aanleveren aan dezelfde JavaScript-functie:

```javascript
window.updateMarineData({
  connected: true,
  latitudeDisplay: "53° 17.4669′ N",
  longitudeDisplay: "005° 05.3104′ E",
  latitudeDecimal: 53.291115,
  longitudeDecimal: 5.088506,
  depthRaw: 4.1,
  depthOffset: 0.3
});
```
