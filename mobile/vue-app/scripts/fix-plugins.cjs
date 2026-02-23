const fs = require('fs');
const path = require('path');

const pluginsFile = path.join(__dirname, '../android/app/src/main/assets/capacitor.plugins.json');

console.log('Checking capacitor.plugins.json...');

if (fs.existsSync(pluginsFile)) {
    const plugins = JSON.parse(fs.readFileSync(pluginsFile, 'utf8'));

    // Check if ObjectDetection exists
    const exists = plugins.some(p => p.pkg === 'ObjectDetection');

    if (!exists) {
        console.log('Adding ObjectDetection plugin...');
        plugins.push({
            pkg: 'ObjectDetection',
            classpath: 'com.walksense.app.ObjectDetectionPlugin'
        });

        fs.writeFileSync(pluginsFile, JSON.stringify(plugins, null, '\t'));
        console.log('ObjectDetection plugin added!');
    } else {
        console.log('ObjectDetection plugin already exists');
    }
} else {
    console.error('capacitor.plugins.json not found');
}