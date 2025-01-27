const { onValueCreated } = require('firebase-functions/v2/database');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { onRequest } = require('firebase-functions/v2/https');
const admin = require('firebase-admin');
const geohash = require('ngeohash');
const cheerio = require('cheerio'); // For HTML parsing
const math = require('mathjs');

const fetch = globalThis.fetch;

admin.initializeApp();

// cloud function  to round timestamp to the nearest hour and format as "YYYY-MM-DD HH:00:00"
function getHourTime(timestamp) {
  const date = new Date(timestamp);
  date.setMinutes(0, 0, 0);

  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  const hour = String(date.getUTCHours()).padStart(2, '0');

  return `${year}-${month}-${day} ${hour}:00:00`;
}

// function to round timestamp to the nearest hour for time slot keys
function getTimeSlot(timestamp) {
  const date = new Date(timestamp);
  date.setMinutes(0, 0, 0);

  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  const hour = String(date.getUTCHours()).padStart(2, '0');

  return `${year}${month}${day}T${hour}`;
}

// function to get the location name from coordinates using geocoding API
async function getLocationName(latitude, longitude) {
  try {
    const apiKey = '';
    const apiUrl = `https://api.opencagedata.com/geocode/v1/json?q=${latitude},${longitude}&key=${apiKey}`;

    const response = await fetch(apiUrl);

    if (!response.ok) {
      console.error(`Geocoding API error: ${response.statusText}`);
      return null;
    }

    const data = await response.json();
    if (data.results && data.results.length > 0) {
      return data.results[0].formatted;
    }

    console.warn('No results from geocoding API.');
    return null;
  } catch (error) {
    console.error('Error in getLocationName:', error);
    return null;
  }
}

// `onValueCreated` trigger for realtime database
exports.aggregateSensorData = onValueCreated(
  {
    ref: '/light_sensor_data/{pushId}',
    region: 'europe-west1',
  },
  async (event) => {
    try {
      const data = event.data.val();
      console.log('New data received:', data);

      // validate required fields
      if (
        typeof data.latitude !== 'number' || isNaN(data.latitude) ||
        typeof data.longitude !== 'number' || isNaN(data.longitude) ||
        data.latitude === 0 || data.longitude === 0 ||
        typeof data.light_level !== 'number' || isNaN(data.light_level)
      ) {
        console.error('Invalid data format or coordinates:', data);
        return null;
      }

      const latitude = data.latitude;
      const longitude = data.longitude;
      const timestamp = data.timestamp || Date.now();
      const lightLevel = data.light_level;


      const geoHash = geohash.encode(latitude, longitude, 5);
      console.log('GeoHash:', geoHash);


      const timeSlot = getTimeSlot(timestamp);
      const hourTime = getHourTime(timestamp);
      console.log('Time Slot:', timeSlot, 'Hour Time:', hourTime);

      //  location assignment
      const locationName = "County Dublin";


      const aggRef = admin.database().ref(`/aggregated_data/${geoHash}/${timeSlot}`);


      await aggRef.transaction((currentData) => {
        if (currentData === null) {
          console.log('Initializing new aggregation entry.');
          return {
            totalLightLevel: lightLevel,
            count: 1,
            averageLightLevel: lightLevel,
            latitude: latitude,
            longitude: longitude,
            hour_time: hourTime,
            location: locationName,
          };
        } else {
          console.log('Updating existing aggregation entry.');
          const newTotal = currentData.totalLightLevel + lightLevel;
          const newCount = currentData.count + 1;
          return {
            totalLightLevel: newTotal,
            count: newCount,
            averageLightLevel: newTotal / newCount,
            latitude: (currentData.latitude * currentData.count + latitude) / newCount,
            longitude: (currentData.longitude * currentData.count + longitude) / newCount,
            hour_time: hourTime,
            location: locationName,
          };
        }
      });

      console.log('Aggregation successful.');
    } catch (error) {
      console.error('Error in aggregateSensorData:', error);
    }
  }
);

// function for processing the CSV data
async function fetchAndProcessCSVData() {
  try {
    // fetching the CSV data using API
    const response = await fetch('http://www.unihedron.com/projects/darksky/database/index.php?csv=true');

    if (!response.ok) {
      throw new Error(`Unexpected response ${response.statusText}`);
    }

    const htmlContent = await response.text();

    //
    const preContentMatch = htmlContent.match(/<pre>([\s\S]*?)<\/pre>/);
    if (!preContentMatch || preContentMatch.length < 2) {
      throw new Error('CSV data not found in the page content.');
    }

    const csvData = preContentMatch[1]; // Extract the CSV-like data from <pre> tags
    console.log('Raw CSV Data:', csvData);

    // split CSV data into lines and process
    const lines = csvData.trim().split('\n');
    const headers = lines[0].split(',').map((header) => header.trim()); // Extract headers
    const records = lines.slice(1).map((line) => {
      const values = line.split(',').map((value) => value.trim());
      return headers.reduce((record, header, index) => {
        record[header] = values[index];
        return record;
      }, {});
    });

    if (records.length === 0) {
      console.error('No entries found in CSV data.');
      return;
    }

    console.log(`Parsed ${records.length} records from CSV data.`);

    // fetch existing data from Firebase to check for duplicates
    const existingData = await fetchExistingData();

    // process and store records
    const updates = {};
    for (const record of records.slice(0, 1)) { // Adjust limit as needed
      const uniqueIdentifier = record['UT_datetime'];
      if (uniqueIdentifier && existingData.has(uniqueIdentifier)) {
        console.log(`Skipping duplicate entry with timestamp: ${uniqueIdentifier}`);
        continue;
      }

      const timestamp = Date.now();
      record['negative_timestamp'] = (-timestamp).toString();
      const key = `key_${-timestamp}`;
      updates[`/open_data/${key}`] = record;
    }

    if (Object.keys(updates).length > 0) {
      await admin.database().ref().update(updates);
      console.log('Data successfully written to Firebase.');
    } else {
      console.log('No new data to write to Firebase.');
    }
  } catch (error) {
    console.error('Error in fetchAndProcessCSVData:', error);
  }
}

// function to fetch existing data from Firebase
async function fetchExistingData() {
  const existingData = new Set();

  try {
    const snapshot = await admin.database().ref('/open_data').once('value');

    if (snapshot.exists()) {
      snapshot.forEach((child) => {
        const utDatetime = child.child('UT_datetime').val();
        if (utDatetime) {
          existingData.add(utDatetime);
        }
      });
    }

    console.log(`Fetched ${existingData.size} existing entries from Firebase.`);
  } catch (error) {
    console.error('Error fetching existing data:', error);
  }

  return existingData;
}


// use the `onSchedule` API for periodic tasks
exports.scheduledDataFetch = onSchedule(
  {
    schedule: '1 * * * *', // Every 1 minute past the hour
    timeZone: 'Etc/UTC', // Set to UTC timezone (adjust as needed)
    region: 'europe-west1', // Match your database region
  },
  async () => {
    console.log('Starting scheduled data fetch.');
    await fetchAndProcessCSVData();
    console.log('Scheduled data fetch completed.');
    return null;
  }
);

// function to fetch and process cloud cover data from the API
async function fetchAndProcessCloudDataFromAPI() {
  try {
    const lat = 53.3498; // Dublin latitude
    const lon = -6.2603; // Dublin longitude
    const apiUrl = `https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${lat}&lon=${lon}`;

    // Fetch the weather data
    const response = await fetch(apiUrl, {
      headers: {
        'User-Agent': 'LightSensorLogger2/1.0 (bhadmusm@tcd.ie)', // Replace with your app name and contact info
      },
    });

    if (!response.ok) {
      throw new Error(`Unexpected response ${response.statusText}`);
    }

    const data = await response.json();

    // extracting the current time data point
    const timeseries = data.properties.timeseries;
    if (!timeseries || timeseries.length === 0) {
      throw new Error('No timeseries data available.');
    }

    const currentData = timeseries[0];
    const timeISO = currentData.time;
    const cloudCover = currentData.data.instant.details.cloud_area_fraction;

    // parse and format the time
    const dateObj = new Date(timeISO);
    const formattedTime = formatDateTime(dateObj);


    const cloudData = {
      time: formattedTime,
      cloud_area_fraction: cloudCover,
      timestamp: Date.now(),
    };

    console.log('Extracted cloud data:', cloudData);

    // check for duplicates
    const existingData = await fetchExistingCloudData();
    const uniqueIdentifier = cloudData.time;

    if (uniqueIdentifier && existingData.has(uniqueIdentifier)) {
      console.log(`Skipping duplicate cloud data entry with time: ${uniqueIdentifier}`);
      return;
    }

    // saving data to Firebase
    const key = `key_${-cloudData.timestamp}`;
    await admin.database().ref(`/cloud_data/${key}`).set(cloudData);

    console.log('Cloud data successfully written to Firebase.');
  } catch (error) {
    console.error('Error in fetchAndProcessCloudDataFromAPI:', error);
  }
}

// format helper function
function formatDateTime(dateObj) {
  const year = dateObj.getUTCFullYear();
  const month = String(dateObj.getUTCMonth() + 1).padStart(2, '0'); // Months are zero-based
  const day = String(dateObj.getUTCDate()).padStart(2, '0');
  const hours = String(dateObj.getUTCHours()).padStart(2, '0');
  const minutes = String(dateObj.getUTCMinutes()).padStart(2, '0');
  const seconds = String(dateObj.getUTCSeconds()).padStart(2, '0');

  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`; // "YYYY-MM-DD HH:mm:ss"
}


// function to fetch existing cloud data to prevent duplicates
async function fetchExistingCloudData() {
  const existingData = new Set();

  try {
    const snapshot = await admin.database().ref('/cloud_data').once('value');

    if (snapshot.exists()) {
      snapshot.forEach((child) => {
        const time = child.child('time').val();
        if (time) {
          existingData.add(time);
        }
      });
    }

    console.log(`Fetched ${existingData.size} existing cloud data entries from Firebase.`);
  } catch (error) {
    console.error('Error fetching existing cloud data:', error);
  }

  return existingData;
}

// schedule the cloud data fetch function to run every hour
exports.scheduledCloudDataFetch = onSchedule(
  {
    schedule: '1 * * * *',
    timeZone: 'Etc/UTC',
    region: 'europe-west1',
  },
  async () => {
    console.log('Starting scheduled cloud data fetch from API.');
    await fetchAndProcessCloudDataFromAPI();
    console.log('Scheduled cloud data fetch from API completed.');
    return null;
  }
);



// function to perform data fusion and analysis
exports.performDataFusionAndAnalysis = onSchedule(
  {
    schedule: '2 * * * *', // Every hour at minute 5
    timeZone: 'Etc/UTC',
    region: 'europe-west1',
  },
  async (event) => {
    try {
      console.log('Starting data fusion and analysis.');

    // fetch data from Firebase
    const aggregatedDataSnapshot = await admin.database().ref('/aggregated_data').once('value');
    const cloudDataSnapshot = await admin.database().ref('/cloud_data').once('value');
    const openDataSnapshot = await admin.database().ref('/open_data').once('value');

    // process data
    const aggregatedData = processAggregatedData(aggregatedDataSnapshot);
    const cloudData = processCloudData(cloudDataSnapshot);
    const openData = processOpenData(openDataSnapshot);

    // merge data
    const unifiedData = mergeData(aggregatedData, cloudData, openData);

    // perform analysis
    const analysisResults = performAnalysis(unifiedData);

    // store analysis results in firebase
    await admin.database().ref('/analysis_results').set(analysisResults);

    console.log('Data fusion and analysis completed.');
  } catch (error) {
    console.error('Error in performDataFusionAndAnalysis:', error);
  }
});

// function to process aggregated data
function processAggregatedData(snapshot) {
  const data = {};

  snapshot.forEach((geoHashSnapshot) => {
    geoHashSnapshot.forEach((timeSlotSnapshot) => {
      const value = timeSlotSnapshot.val();
      const hourTime = value.hour_time;
      data[hourTime] = {
        lightLevel: value.averageLightLevel,
        location: value.location,
      };
    });
  });

  return data;
}

// function to process cloud data
function processCloudData(snapshot) {
  const data = {};

  snapshot.forEach((childSnapshot) => {
    const value = childSnapshot.val();
    const time = value.time;
    data[time] = {
      cloudCover: value.cloud_area_fraction,
    };
  });

  return data;
}

// function to process open data
function processOpenData(snapshot) {
  const data = {};

  snapshot.forEach((childSnapshot) => {
    const value = childSnapshot.val();
    const time = value.UT_datetime;
    data[time] = {
      lightLevel: parseFloat(value.Brightness),
      cloudCover: parseCloudConditions(value.Conditions),
      location: value['Site description'],
    };
  });

  return data;
}

// function to parse cloud conditions to numerical value
function parseCloudConditions(conditions) {
    if (!conditions || typeof conditions !== 'string') {
    console.warn('Invalid or missing cloud conditions:', conditions);
    return 0;
  }

  const match = conditions.match(/(\d+)%/);
  if (match && match[1]) {
    return parseFloat(match[1]);
  }
  return 0; // Default to 0 if no match
}

// fucntion to merge data
function mergeData(aggregatedData, cloudData, openData) {
  const unifiedData = {};

  // get all unique times
  const allTimes = new Set([
    ...Object.keys(aggregatedData),
    ...Object.keys(cloudData),
    ...Object.keys(openData),
  ]);

  // merge data
  allTimes.forEach((time) => {
    unifiedData[time] = {
      time: time,
      lightLevelAggregated: aggregatedData[time]?.lightLevel || 0,
      cloudCoverAggregated: cloudData[time]?.cloudCover || 0,
      locationAggregated: aggregatedData[time]?.location || '',
      lightLevelOpen: openData[time]?.lightLevel || 0,
      cloudCoverOpen: openData[time]?.cloudCover || 0,
      locationOpen: openData[time]?.location || '',
    };
  });

  return unifiedData;
}

// function to perform analysis
function performAnalysis(unifiedData) {
  const times = [];
  const lightLevelsAggregated = [];
  const cloudCoversAggregated = [];
  const lightLevelsOpen = [];
  const cloudCoversOpen = [];

  for (const time in unifiedData) {
    const entry = unifiedData[time];
    times.push(time);
    lightLevelsAggregated.push(entry.lightLevelAggregated);
    cloudCoversAggregated.push(entry.cloudCoverAggregated);
    lightLevelsOpen.push(entry.lightLevelOpen);
    cloudCoversOpen.push(entry.cloudCoverOpen);
  }

  // calculate correlations
  const correlationAggregated = calculateCorrelation(lightLevelsAggregated, cloudCoversAggregated);
  const correlationOpen = calculateCorrelation(lightLevelsOpen, cloudCoversOpen);

  // prep analysis results
  const analysisResults = {
    correlationAggregated: correlationAggregated,
    correlationOpen: correlationOpen,
    times: times,
    lightLevelsAggregated: lightLevelsAggregated,
    cloudCoversAggregated: cloudCoversAggregated,
    lightLevelsOpen: lightLevelsOpen,
    cloudCoversOpen: cloudCoversOpen,
  };

  return analysisResults;
}

// function to calculate Pearson correlation coefficient
function calculateCorrelation(x, y) {
  if (x.length !== y.length || x.length === 0) {
    return null;
  }

  const xMean = math.mean(x);
  const yMean = math.mean(y);

  const numerator = x.reduce((sum, xi, idx) => sum + (xi - xMean) * (y[idx] - yMean), 0);
  const denominator = Math.sqrt(
    x.reduce((sum, xi) => sum + Math.pow(xi - xMean, 2), 0) *
    y.reduce((sum, yi) => sum + Math.pow(yi - yMean, 2), 0)
  );

  if (denominator === 0) {
    return null;
  }

  return numerator / denominator;
}
