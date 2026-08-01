const marineData = {
  connected: false,
  latitudeDisplay: "53° 17.4669′ N",
  longitudeDisplay: "005° 05.3104′ E",
  latitudeDecimal: 53.291115,
  longitudeDecimal: 5.088506,
  depthRaw: 4.1,
  depthOffset: 0.3
};

function formatSigned(value, digits = 1) {
  const sign = value >= 0 ? "+" : "";
  return `${sign}${value.toFixed(digits)}`;
}

function updateDashboard(data) {
  const correctedDepth = data.depthRaw + data.depthOffset;

  document.getElementById("latitude").textContent = data.latitudeDisplay;
  document.getElementById("longitude").textContent = data.longitudeDisplay;
  document.getElementById("latitudeDecimal").textContent =
    `${data.latitudeDecimal.toFixed(6)}°`;
  document.getElementById("longitudeDecimal").textContent =
    `${data.longitudeDecimal.toFixed(6)}°`;

  document.getElementById("depthValue").textContent =
    correctedDepth.toFixed(1);
  document.getElementById("depthRaw").textContent =
    `${data.depthRaw.toFixed(1)} m`;
  document.getElementById("depthOffset").textContent =
    `${formatSigned(data.depthOffset)} m`;

  const status = document.getElementById("status");
  const statusText = document.getElementById("statusText");

  if (data.connected) {
    status.classList.add("status--connected");
    status.classList.remove("status--test");
    statusText.textContent = "Verbonden";
  } else {
    status.classList.remove("status--connected");
    status.classList.add("status--test");
    statusText.textContent = "Testgegevens";
  }

  document.getElementById("lastUpdate").textContent =
    `Laatst bijgewerkt: ${new Date().toLocaleTimeString("nl-NL")}`;
}

updateDashboard(marineData);

/*
Later vervangen we marineData door live gegevens uit de Samsung-app.

De Android-laag kan bijvoorbeeld dit aanroepen:

window.updateMarineData({
  connected: true,
  latitudeDisplay: "53° 17.4669′ N",
  longitudeDisplay: "005° 05.3104′ E",
  latitudeDecimal: 53.291115,
  longitudeDecimal: 5.088506,
  depthRaw: 4.1,
  depthOffset: 0.3
});
*/

window.updateMarineData = function updateMarineData(newData) {
  Object.assign(marineData, newData);
  updateDashboard(marineData);
};
