const marineData = {
  connected: false,
  statusText: "Wachten op NMEA…",
  latitudeDisplay: "—",
  longitudeDisplay: "—",
  positionAvailable: false,
  depthRaw: null,
  depthOffset: 0,
  depthAvailable: false,
  trueWindDirection: null,
  trueWindSpeed: null,
  windAvailable: false,
  lastUpdateMillis: null
};

const HOUR_MS = 60 * 60 * 1000;
const WIND_WINDOW_MS = HOUR_MS;
const DEPTH_WINDOW_MS = 24 * HOUR_MS;

const windHistory = [];
const depthHistory = [];

let windSpeedMaximum = 32;

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
    const corrected =
      data.depthRaw + (Number(data.depthOffset) || 0);

    document.getElementById("depthValue").textContent =
      corrected.toFixed(1);

    document.getElementById("depthRaw").textContent =
      `${data.depthRaw.toFixed(1)} m`;

    document.getElementById("depthOffset").textContent =
      `${signed(Number(data.depthOffset) || 0)} m`;
  } else {
    document.getElementById("depthValue").textContent = "—";
    document.getElementById("depthRaw").textContent = "—";
    document.getElementById("depthOffset").textContent = "—";
  }

  if (
    data.windAvailable &&
    Number.isFinite(data.trueWindDirection) &&
    Number.isFinite(data.trueWindSpeed)
  ) {
    document.getElementById("trueWindDirection").textContent =
      `${Math.round(data.trueWindDirection)
        .toString()
        .padStart(3, "0")}°`;

    document.getElementById("trueWindSpeed").textContent =
      data.trueWindSpeed.toFixed(1);
  } else {
    document.getElementById("trueWindDirection").textContent = "—";
    document.getElementById("trueWindSpeed").textContent = "—";
  }

  const status = document.getElementById("status");

  status.classList.toggle(
    "status--connected",
    Boolean(data.connected)
  );

  status.classList.toggle(
    "status--error",
    data.connected === false &&
      String(data.statusText || "")
        .toLowerCase()
        .includes("fout")
  );

  document.getElementById("statusText").textContent =
    data.statusText ||
    (data.connected ? "Live NMEA" : "Wachten op NMEA…");

  if (data.lastUpdateMillis) {
    document.getElementById("lastUpdate").textContent =
      `Laatst bijgewerkt: ${new Date(
        data.lastUpdateMillis
      ).toLocaleTimeString("nl-NL")}`;
  }
}

window.updateMarineData = function updateMarineData(newData) {
  Object.assign(marineData, newData);
  updateDashboard(marineData);
};

window.addHistoryPoint = function addHistoryPoint(point) {
  if (!Number.isFinite(point.timestamp)) {
    return;
  }

  if (
    Number.isFinite(point.windDirection) &&
    Number.isFinite(point.windSpeed)
  ) {
    windHistory.push({
      timestamp: point.timestamp,
      direction: point.windDirection,
      speed: point.windSpeed
    });
  }

  if (Number.isFinite(point.depth)) {
    depthHistory.push({
      timestamp: point.timestamp,
      depth: point.depth
    });
  }

  trimHistory(windHistory, Date.now() - WIND_WINDOW_MS);
  trimHistory(depthHistory, Date.now() - DEPTH_WINDOW_MS);

  const highestSpeed = windHistory.reduce(
    (maximum, item) => Math.max(maximum, item.speed),
    0
  );

  windSpeedMaximum = highestSpeed > 32 ? 62 : 32;

  drawWindChart();
  drawDepthChart();
};

function trimHistory(history, cutoff) {
  while (
    history.length > 0 &&
    history[0].timestamp < cutoff
  ) {
    history.shift();
  }
}

function cssColor(name) {
  return getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
}

function prepareCanvas(id) {
  const canvas = document.getElementById(id);
  const wrapper = canvas.parentElement;
  const ratio = window.devicePixelRatio || 1;
  const width = Math.max(1, wrapper.clientWidth);
  const height = Math.max(1, wrapper.clientHeight);

  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);

  const context = canvas.getContext("2d");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, width, height);

  return { canvas, context, width, height };
}

function drawWindChart() {
  const { context, width, height } =
    prepareCanvas("windChart");

  const margin = {
    top: 12,
    right: 48,
    bottom: 34,
    left: 48
  };

  const plot = makePlot(width, height, margin);
  const colors = chartColors();

  context.font =
    '11px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
  context.textBaseline = "middle";

  for (let division = 0; division <= 16; division += 1) {
    const fraction = division / 16;
    const y = plot.bottom - fraction * plot.height;
    const strong = division % 4 === 0;

    drawHorizontalGrid(
      context,
      plot,
      y,
      strong ? colors.gridStrong : colors.grid
    );

    if (strong) {
      context.fillStyle = colors.text;
      context.textAlign = "right";
      context.fillText(
        `${Math.round(division * 22.5)}°`,
        plot.left - 8,
        y
      );

      context.textAlign = "left";
      const speedValue = windSpeedMaximum * fraction;
      context.fillText(
        formatSpeedAxis(speedValue),
        plot.right + 8,
        y
      );
    }
  }

  for (let minute = 0; minute <= 60; minute += 5) {
    const x =
      plot.right - minute / 60 * plot.width;

    drawVerticalGrid(
      context,
      plot,
      x,
      minute % 15 === 0
        ? colors.gridStrong
        : colors.grid
    );

    if (minute % 15 === 0) {
      context.fillStyle = colors.text;
      context.textAlign =
        minute === 0
          ? "right"
          : minute === 60
            ? "left"
            : "center";

      context.fillText(
        minute === 0 ? "nu" : `-${minute} min`,
        x,
        plot.bottom + 20
      );
    }
  }

  drawTimeSeries(
    context,
    windHistory,
    "direction",
    colors.direction,
    plot,
    WIND_WINDOW_MS,
    360,
    true,
    false
  );

  drawTimeSeries(
    context,
    windHistory,
    "speed",
    colors.speed,
    plot,
    WIND_WINDOW_MS,
    windSpeedMaximum,
    false,
    false
  );
}

function drawDepthChart() {
  const { context, width, height } =
    prepareCanvas("depthChart");

  const margin = {
    top: 12,
    right: 20,
    bottom: 34,
    left: 48
  };

  const plot = makePlot(width, height, margin);
  const colors = chartColors();

  context.font =
    '11px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
  context.textBaseline = "middle";

  for (let meter = 0; meter <= 10; meter += 1) {
    const y = plot.top + meter / 10 * plot.height;

    drawHorizontalGrid(
      context,
      plot,
      y,
      meter % 5 === 0
        ? colors.gridStrong
        : colors.grid
    );

    context.fillStyle = colors.text;
    context.textAlign = "right";
    context.fillText(
      `${meter} m`,
      plot.left - 8,
      y
    );
  }

  for (let hour = 0; hour <= 24; hour += 3) {
    const x =
      plot.right - hour / 24 * plot.width;

    drawVerticalGrid(
      context,
      plot,
      x,
      hour % 6 === 0
        ? colors.gridStrong
        : colors.grid
    );

    context.fillStyle = colors.text;
    context.textAlign =
      hour === 0
        ? "right"
        : hour === 24
          ? "left"
          : "center";

    context.fillText(
      hour === 0 ? "nu" : `-${hour} u`,
      x,
      plot.bottom + 20
    );
  }

  drawTimeSeries(
    context,
    depthHistory,
    "depth",
    colors.depth,
    plot,
    DEPTH_WINDOW_MS,
    10,
    false,
    true
  );
}

function makePlot(width, height, margin) {
  const left = margin.left;
  const right = width - margin.right;
  const top = margin.top;
  const bottom = height - margin.bottom;

  return {
    left,
    right,
    top,
    bottom,
    width: right - left,
    height: bottom - top
  };
}

function chartColors() {
  return {
    text: cssColor("--muted"),
    grid: cssColor("--grid"),
    gridStrong: cssColor("--grid-strong"),
    direction: cssColor("--direction-line"),
    speed: cssColor("--speed-line"),
    depth: cssColor("--depth-line")
  };
}

function drawHorizontalGrid(context, plot, y, color) {
  context.strokeStyle = color;
  context.lineWidth = 1;
  context.beginPath();
  context.moveTo(plot.left, y);
  context.lineTo(plot.right, y);
  context.stroke();
}

function drawVerticalGrid(context, plot, x, color) {
  context.strokeStyle = color;
  context.lineWidth = 1;
  context.beginPath();
  context.moveTo(x, plot.top);
  context.lineTo(x, plot.bottom);
  context.stroke();
}

function drawTimeSeries(
  context,
  points,
  key,
  color,
  plot,
  windowMs,
  maximum,
  breakAtNorth,
  invertVertical
) {
  if (points.length === 0) {
    return;
  }

  const now = Date.now();
  const start = now - windowMs;

  context.strokeStyle = color;
  context.lineWidth = 2;
  context.lineJoin = "round";
  context.lineCap = "round";
  context.beginPath();

  let started = false;
  let previousValue = null;

  for (const point of points) {
    if (
      point.timestamp < start ||
      point.timestamp > now
    ) {
      continue;
    }

    const value = point[key];
    const timeFraction =
      (point.timestamp - start) / windowMs;

    const x =
      plot.left + timeFraction * plot.width;

    const bounded =
      Math.max(0, Math.min(maximum, value));

    const valueFraction = bounded / maximum;

    const y = invertVertical
      ? plot.top + valueFraction * plot.height
      : plot.bottom - valueFraction * plot.height;

    const crossesNorth =
      breakAtNorth &&
      previousValue !== null &&
      Math.abs(value - previousValue) > 180;

    if (!started || crossesNorth) {
      context.moveTo(x, y);
      started = true;
    } else {
      context.lineTo(x, y);
    }

    previousValue = value;
  }

  context.stroke();
}

function formatSpeedAxis(value) {
  if (windSpeedMaximum === 32) {
    return `${Math.round(value)} kn`;
  }

  const rounded = Math.round(value * 10) / 10;
  return `${rounded.toLocaleString("nl-NL")} kn`;
}

window.addEventListener("resize", () => {
  drawWindChart();
  drawDepthChart();
});

updateDashboard(marineData);
drawWindChart();
drawDepthChart();

function selectPage(n) {
  document.querySelectorAll(".page").forEach(p => p.classList.toggle("page--active", p.id === `page${n}`));
  document.querySelectorAll(".tab").forEach(t => t.classList.toggle("tab--active", t.dataset.page === String(n)));
  if (n === 1) requestAnimationFrame(() => { drawWindChart(); drawDepthChart(); });
}
document.querySelectorAll(".tab").forEach(t => t.addEventListener("click", () => selectPage(Number(t.dataset.page))));
selectPage(1);
