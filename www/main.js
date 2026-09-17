import { BackgroundGeolocation } from '@capacitor-community/background-geolocation';

let isTracking = false;
const btn = document.getElementById('btn');
const statusDiv = document.getElementById('status');
const driverSelect = document.getElementById('driverSelect');

// ВАЖНО: Замените этот адрес на реальный IP вашего сервера!
const API_URL = 'https://ваш-сайт.ru/taxi/driver/gps_update.php'; 

btn.addEventListener('click', async () => {
    if (isTracking) {
        await BackgroundGeolocation.stop();
        isTracking = false;
        btn.textContent = '▶️ Начать передачу GPS';
        btn.classList.remove('stop');
        statusDiv.innerHTML = '⏹️ Трекинг остановлен';
        return;
    }

    statusDiv.innerHTML = '🔄 Запуск нативного GPS...';

    try {
        const status = await BackgroundGeolocation.requestPermissions();
        if (status.location !== 'granted' || status.backgroundLocation !== 'granted') {
            statusDiv.innerHTML = '❌ Нужно разрешение "Разрешить всегда"!';
            return;
        }

        await BackgroundGeolocation.initialize({
            notificationTitle: '🚖 Такси Шаран',
            notificationText: 'Отслеживание активно',
            desiredAccuracy: 10,
            distanceFilter: 50, 
            interval: 10000,    
            stopOnTerminate: false, 
            startOnBoot: true,      
            foregroundService: true  
        });

        BackgroundGeolocation.addListener('onLocation', async (location) => {
            const lat = location.latitude;
            const lon = location.longitude;
            const driverId = driverSelect.value;
           
            statusDiv.innerHTML = '✅ Отправлено! ' + lat.toFixed(5) + ', ' + lon.toFixed(5);

            fetch(API_URL, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ driver_id: driverId, lat: lat, lon: lon })
            }).catch(() => {});
        });

        await BackgroundGeolocation.start();
        isTracking = true;
        btn.textContent = '⏹️ Остановить передачу';
        btn.classList.add('stop');

    } catch (error) {
        statusDiv.innerHTML = '❌ Ошибка: ' + error.message;
    }
});
