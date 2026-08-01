const marineData = {
  connected: false,
  statusText: "Wachten op NMEA…",
  latitudeDisplay: "—",
  longitudeDisplay: "—",
  latitudeDecimal: null,
  longitudeDecimal: null,
  depthRaw: null,
  depthOffset: 0,
  positionAvailable: false,
  depthAvailable: false,
  lastUpdateMillis: null
};

function signed(value, digits = 1) {
  const sign = value >= 0 ? "+" : "";
  return `${sign}${value.toFixed(digits)}`;
}

function updateDashboard(data) {
  document.getElementById("latitude").textContent =
    data.positionAvailable ? data.latitudeDisplay : "—";

  document.getElementById("longitude").textContent =
    data.positionAvailable ? data.longitudeDisplay : "—";

  if (data.depthAvailable && Number.isFinite(data.depthRaw)) {
    const corrected = data.depthRaw + (Number(data.depthOffset) || 0);
    document.getElementById("depthValue").textContent = corrected.toFixed(1);
    document.getElementById("depthRaw").textContent =
      `${data.depthRaw.toFixed(1)} m`;
    document.getElementById("depthOffset").textContent =
      `${signed(Number(data.depthOffset) || 0)} m`;
  } else {
    document.getElementById("depthValue").textContent = "—";
    document.getElementById("depthRaw").textContent = "—";
    document.getElementById("depthOffset").textContent = "—";
  }

  const status = document.getElementById("status");
  status.classList.toggle("status--connected", Boolean(data.connected));
  status.classList.toggle("status--error", data.connected === false &&
    String(data.statusText || "").toLowerCase().includes("fout"));

  document.getElementById("statusText").textContent =
    data.statusText || (data.connected ? "Live NMEA" : "Wachten op NMEA…");

  if (data.lastUpdateMillis) {
    document.getElementById("lastUpdate").textContent =
      `Laatst bijgewerkt: ${new Date(data.lastUpdateMillis)
        .toLocaleTimeString("nl-NL")}`;
  }
}

window.updateMarineData = function updateMarineData(newData) {
  Object.assign(marineData, newData);
  updateDashboard(marineData);
};

updateDashboard(marineData);
