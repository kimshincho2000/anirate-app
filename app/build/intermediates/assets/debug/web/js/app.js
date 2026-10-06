/**
 * AniRate Mobile Client SPA
 * Fully offline-capable, native-integrated frontend.
 */

const API_BASE = 'https://anirate.wwwz.uz/api.php';

let currentView = 'home';
let viewHistory = ['home'];
let heroSwiperInstance = null;
let currentAnimeData = null;
let catalogPage = 1;
let currentGenreFilter = '';
let searchTimeout = null;

// Initialize on DOM Ready
document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

function initApp() {
    // 1. Immediately render cached home data if available (0ms instant startup!)
    const cachedHome = localStorage.getItem('anirate_cached_home');
    if (cachedHome) {
        try {
            renderHomeScreen(JSON.parse(cachedHome));
        } catch (e) {}
    }

    // 2. Fetch fresh home data from API
    fetchHomeData();

    // 3. Setup Live Search Listener
    const searchInput = document.getElementById('liveSearchInput');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.trim();
            const clearBtn = document.getElementById('btnSearchClear');
            if (clearBtn) clearBtn.style.display = query ? 'block' : 'none';

            clearTimeout(searchTimeout);
            if (!query) {
                document.getElementById('searchResultsGrid').innerHTML = '';
                document.getElementById('searchEmptyState').style.display = 'block';
                return;
            }
            searchTimeout = setTimeout(() => {
                performSearch(query);
            }, 350);
        });
    }

    // 4. Update downloads count badge from native storage
    updateDownloadsBadge();

    // 5. Expose bridge for Android Native back button
    window.handleNativeBackPressed = function() {
        const playerModal = document.getElementById('playerModal');
        if (playerModal && playerModal.style.display === 'flex') {
            closePlayerModal();
            return true;
        }
        if (viewHistory.length > 1) {
            navigateBack();
            return true;
        }
        return false; // Exit app
    };
}

// =========================================================================
// ROUTER & NAVIGATION
// =========================================================================

function navigateTo(viewName, addToHistory = true) {
    if (currentView === viewName && viewName !== 'detail') return;

    if (addToHistory && viewName !== currentView) {
        viewHistory.push(viewName);
    }
    currentView = viewName;

    // Update View Containers
    document.querySelectorAll('.app-view').forEach(el => el.classList.remove('active'));
    const targetView = document.getElementById(`view-${viewName}`);
    if (targetView) targetView.classList.add('active');

    // Update Bottom Nav Tabs
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    const targetTab = document.getElementById(`tab-${viewName}`);
    if (targetTab) targetTab.classList.add('active');

    window.scrollTo({ top: 0, behavior: 'smooth' });

    // Haptic feedback
    vibrateNative(15);

    // Specific View Initializers
    if (viewName === 'catalog') {
        if (!document.getElementById('catalogGrid').children.length) {
            loadCatalog(1);
        }
    } else if (viewName === 'downloads') {
        refreshDownloadedFiles();
    } else if (viewName === 'search') {
        setTimeout(() => {
            const input = document.getElementById('liveSearchInput');
            if (input) input.focus();
        }, 200);
    }
}

function navigateBack() {
    if (viewHistory.length > 1) {
        viewHistory.pop();
        const prevView = viewHistory[viewHistory.length - 1];
        navigateTo(prevView, false);
    } else {
        navigateTo('home', false);
    }
}

// =========================================================================
// 1. HOME VIEW LOGIC
// =========================================================================

async function fetchHomeData() {
    try {
        const res = await fetch(`${API_BASE}?action=home`, { cache: 'no-cache' });
        const json = await res.json();
        if (json.ok && json.data) {
            localStorage.setItem('anirate_cached_home', JSON.stringify(json.data));
            renderHomeScreen(json.data);
            hideOfflineBar();
        }
    } catch (err) {
        console.warn('Network offline or error, using cache:', err);
        showOfflineBar();
    }
}

function renderHomeScreen(data) {
    // 1. Render Hero Swiper
    const heroWrapper = document.getElementById('heroSwiperWrapper');
    if (heroWrapper && data.featured && data.featured.length > 0) {
        heroWrapper.innerHTML = data.featured.map(item => `
            <div class="swiper-slide hero-slide-card" onclick="openAnimeDetail(${item.id})">
                <img src="${item.poster}" alt="${escapeHtml(item.title)}" class="hero-slide-img" loading="lazy">
                <div class="hero-slide-overlay">
                    <span class="hero-slide-badge"><i class="fa-solid fa-fire"></i> Tavsiya</span>
                    <div class="hero-slide-title">${escapeHtml(item.title)}</div>
                    <div class="hero-slide-meta">
                        <span><i class="fa-solid fa-star" style="color:#ffd700;"></i> ${item.rating || '9.5'}</span>
                        <span>•</span>
                        <span>${escapeHtml(item.genres || 'Anime')}</span>
                        <span>•</span>
                        <span>${item.year || '2024'}</span>
                    </div>
                </div>
            </div>
        `).join('');

        if (heroSwiperInstance) heroSwiperInstance.destroy();
        heroSwiperInstance = new Swiper('#heroSwiper', {
            slidesPerView: 'auto',
            centeredSlides: true,
            spaceBetween: 14,
            loop: true,
            autoplay: { delay: 4000, disableOnInteraction: false },
        });
    }

    // 2. Render Genre Quick Chips
    const genreScroll = document.getElementById('homeGenreScroll');
    if (genreScroll && data.genres && data.genres.length > 0) {
        genreScroll.innerHTML = `<div class="category-chip ${currentGenreFilter === '' ? 'active' : ''}" onclick="filterByGenre('')">Barchasi</div>` +
            data.genres.map(g => `
                <div class="category-chip ${currentGenreFilter === g.name ? 'active' : ''}" onclick="filterByGenre('${escapeHtml(g.name)}')">
                    ${escapeHtml(g.name)}
                </div>
            `).join('');
    }

    // 3. Render Latest Anime Grid
    const latestGrid = document.getElementById('homeLatestGrid');
    if (latestGrid && data.latest) {
        latestGrid.innerHTML = data.latest.map(item => createAnimeCardHtml(item)).join('');
    }

    // 4. Render Top Rated Grid
    const topGrid = document.getElementById('homeTopGrid');
    if (topGrid && data.top) {
        topGrid.innerHTML = data.top.map(item => createAnimeCardHtml(item)).join('');
    }
}

function filterByGenre(genre) {
    currentGenreFilter = genre;
    document.querySelectorAll('#homeGenreScroll .category-chip').forEach(el => {
        el.classList.toggle('active', el.textContent.trim() === (genre || 'Barchasi'));
    });
    // Open catalog filtered by this genre
    const typeSelect = document.getElementById('catalogTypeSelect');
    if (typeSelect) typeSelect.value = '';
    loadCatalog(1, false, genre);
    navigateTo('catalog');
}

// =========================================================================
// 2. CATALOG VIEW LOGIC
// =========================================================================

async function loadCatalog(page = 1, append = false, forcedGenre = null) {
    const grid = document.getElementById('catalogGrid');
    const loadMoreBox = document.getElementById('catalogLoadMoreBox');
    const type = document.getElementById('catalogTypeSelect').value;
    const sort = document.getElementById('catalogSortSelect').value;
    const genre = forcedGenre !== null ? forcedGenre : currentGenreFilter;

    if (!append) {
        catalogPage = 1;
        grid.innerHTML = Array(6).fill('<div class="anime-card skeleton" style="height: 180px;"></div>').join('');
    }

    try {
        const url = `${API_BASE}?action=catalog&page=${page}&per_page=18&type=${encodeURIComponent(type)}&sort=${encodeURIComponent(sort)}&genre=${encodeURIComponent(genre)}`;
        const res = await fetch(url);
        const json = await res.json();

        if (json.ok && json.data) {
            const items = json.data.items || [];
            if (!append) grid.innerHTML = '';

            if (items.length === 0 && !append) {
                grid.innerHTML = '<div class="empty-state" style="grid-column: 1/-1;"><i class="fa-solid fa-film"></i><div>Hech qanday anime topilmadi</div></div>';
                loadMoreBox.style.display = 'none';
                return;
            }

            items.forEach(item => {
                grid.insertAdjacentHTML('beforeend', createAnimeCardHtml(item));
            });

            loadMoreBox.style.display = (json.data.page < json.data.total_pages) ? 'block' : 'none';
            catalogPage = page;
        }
    } catch (err) {
        console.error('Catalog fetch error:', err);
    }
}

function loadMoreCatalog() {
    loadCatalog(catalogPage + 1, true);
}

// =========================================================================
// 3. DETAIL VIEW LOGIC
// =========================================================================

async function openAnimeDetail(animeId) {
    navigateTo('detail');

    // Show loading placeholders
    document.getElementById('detailTitle').textContent = 'Yuklanmoqda...';
    document.getElementById('detailPosterImg').src = 'img/icon-192.png';
    document.getElementById('detailBackdrop').style.backgroundImage = 'none';
    document.getElementById('detailEpisodesList').innerHTML = '<div class="skeleton" style="height: 60px; border-radius: 12px;"></div>';
    document.getElementById('detailSimilarGrid').innerHTML = '';

    try {
        const res = await fetch(`${API_BASE}?action=anime&id=${animeId}`);
        const json = await res.json();

        if (json.ok && json.data) {
            const { anime, episodes, similar } = json.data;
            currentAnimeData = json.data;

            document.getElementById('detailTitle').textContent = anime.title || 'Anime';
            document.getElementById('detailPosterImg').src = anime.poster || 'img/icon-192.png';
            document.getElementById('detailBackdrop').style.backgroundImage = `url('${anime.poster}')`;
            document.getElementById('detailGenres').textContent = anime.genres || 'Anime';
            document.getElementById('detailYear').textContent = anime.year || '2024';
            document.getElementById('detailStatus').textContent = anime.status || 'Davom etmoqda';
            document.getElementById('detailRating').textContent = anime.rating || '9.0';
            document.getElementById('detailDescription').textContent = anime.description || "Tavsif mavjud emas.";
            document.getElementById('detailEpCount').textContent = episodes.length;

            // Setup Quick Buttons
            const btnPlayFirst = document.getElementById('btnPlayFirst');
            const btnDownloadFirst = document.getElementById('btnDownloadFirst');

            if (episodes && episodes.length > 0) {
                const firstEp = episodes[0];
                btnPlayFirst.onclick = () => playEpisodeVideo(firstEp, anime);
                btnDownloadFirst.onclick = () => startEpisodeDownload(firstEp, anime);
                btnPlayFirst.style.display = 'flex';
                btnDownloadFirst.style.display = 'flex';
            } else {
                btnPlayFirst.style.display = 'none';
                btnDownloadFirst.style.display = 'none';
            }

            // Render Episodes List
            const epList = document.getElementById('detailEpisodesList');
            if (episodes && episodes.length > 0) {
                epList.innerHTML = episodes.map(ep => `
                    <div class="episode-card-item">
                        <div class="episode-info" onclick="playEpisodeDirect(${ep.qism})">
                            <div class="episode-num-badge">${ep.qism}</div>
                            <div class="episode-title">${ep.title ? escapeHtml(ep.title) : `${ep.qism}-qism`}</div>
                        </div>
                        <div class="episode-actions">
                            <button class="btn-ep-play" onclick="playEpisodeDirect(${ep.qism})" title="Ko'rish">
                                <i class="fa-solid fa-play"></i>
                            </button>
                            <button class="btn-ep-download" onclick="downloadEpisodeDirect(${ep.qism})" title="Yuklab olish">
                                <i class="fa-solid fa-download"></i>
                            </button>
                        </div>
                    </div>
                `).join('');
            } else {
                epList.innerHTML = '<div style="color:#8E8E93; font-size:13px; text-align:center; padding:20px;">Hozircha qismlar yuklanmagan</div>';
            }

            // Render Similar Anime
            const similarGrid = document.getElementById('detailSimilarGrid');
            if (similar && similar.length > 0) {
                similarGrid.innerHTML = similar.map(item => createAnimeCardHtml(item)).join('');
            }
        }
    } catch (err) {
        console.error('Anime detail fetch error:', err);
    }
}

function playEpisodeDirect(qism) {
    if (!currentAnimeData || !currentAnimeData.episodes) return;
    const ep = currentAnimeData.episodes.find(e => e.qism === qism);
    if (ep) playEpisodeVideo(ep, currentAnimeData.anime);
}

function downloadEpisodeDirect(qism) {
    if (!currentAnimeData || !currentAnimeData.episodes) return;
    const ep = currentAnimeData.episodes.find(e => e.qism === qism);
    if (ep) startEpisodeDownload(ep, currentAnimeData.anime);
}

function playEpisodeVideo(episode, anime) {
    let videoUrl = episode.video_url || '';
    if (!videoUrl) {
        showNativeToast("Ushbu qism uchun video manzili topilmadi");
        return;
    }

    const title = `${anime.title} — ${episode.qism}-qism`;
    const playerModal = document.getElementById('playerModal');
    const playerTitle = document.getElementById('playerModalTitle');
    const videoEl = document.getElementById('appVideoElement');

    playerTitle.textContent = title;
    videoEl.src = videoUrl;
    playerModal.style.display = 'flex';
    videoEl.play().catch(() => {});
}

function closePlayerModal() {
    const playerModal = document.getElementById('playerModal');
    const videoEl = document.getElementById('appVideoElement');
    if (videoEl) {
        videoEl.pause();
        videoEl.src = '';
    }
    if (playerModal) playerModal.style.display = 'none';
}

function startEpisodeDownload(episode, anime) {
    const rawUrl = episode.video_url || '';
    if (!rawUrl) {
        showNativeToast("Yuklab olish havolasi topilmadi");
        return;
    }

    const fileName = `${anime.title} — ${episode.qism}-qism.mp4`.replace(/[\\/:*?"<>|]/g, '');
    let downloadUrl = rawUrl;
    if (!downloadUrl.includes('download=1')) {
        downloadUrl += (downloadUrl.includes('?') ? '&' : '?') + 'download=1&name=' + encodeURIComponent(fileName);
    }

    if (window.AniRateNative && typeof window.AniRateNative.downloadVideo === 'function') {
        window.AniRateNative.downloadVideo(downloadUrl, fileName);
    } else {
        window.location.href = downloadUrl;
    }
    showNativeToast(`Yuklab olish boshlandi: ${episode.qism}-qism`);
}

// =========================================================================
// 4. SEARCH VIEW LOGIC
// =========================================================================

async function performSearch(query) {
    const grid = document.getElementById('searchResultsGrid');
    const emptyState = document.getElementById('searchEmptyState');
    grid.innerHTML = Array(6).fill('<div class="anime-card skeleton" style="height: 180px;"></div>').join('');
    emptyState.style.display = 'none';

    try {
        const res = await fetch(`${API_BASE}?action=search&q=${encodeURIComponent(query)}`);
        const json = await res.json();

        if (json.ok && json.data && json.data.items) {
            const items = json.data.items;
            if (items.length === 0) {
                grid.innerHTML = '';
                emptyState.style.display = 'block';
                emptyState.querySelector('div').textContent = `"${query}" bo'yicha hech narsa topilmadi`;
                return;
            }
            grid.innerHTML = items.map(item => createAnimeCardHtml(item)).join('');
        }
    } catch (err) {
        console.error('Search error:', err);
    }
}

function clearSearch() {
    const input = document.getElementById('liveSearchInput');
    if (input) input.value = '';
    document.getElementById('btnSearchClear').style.display = 'none';
    document.getElementById('searchResultsGrid').innerHTML = '';
    document.getElementById('searchEmptyState').style.display = 'block';
}

// =========================================================================
// 5. DOWNLOADS VIEW (LOCAL OFFLINE FILES)
// =========================================================================

function refreshDownloadedFiles() {
    const container = document.getElementById('downloadsListContainer');
    const emptyState = document.getElementById('downloadsEmptyState');

    if (!window.AniRateNative || typeof window.AniRateNative.getDownloadedFiles !== 'function') {
        container.innerHTML = '';
        emptyState.style.display = 'block';
        return;
    }

    try {
        const filesJson = window.AniRateNative.getDownloadedFiles();
        const files = JSON.parse(filesJson || '[]');

        updateDownloadsBadge(files.length);

        if (!files || files.length === 0) {
            container.innerHTML = '';
            emptyState.style.display = 'block';
            return;
        }

        emptyState.style.display = 'none';
        container.innerHTML = files.map(file => `
            <div class="download-item-card">
                <div class="download-item-left" onclick="playLocalVideo('${escapeHtml(file.path)}')">
                    <div class="download-item-icon">
                        <i class="fa-solid fa-play"></i>
                    </div>
                    <div style="flex:1; min-width:0;">
                        <div class="download-item-name">${escapeHtml(file.name)}</div>
                        <div class="download-item-size">${escapeHtml(file.size || 'Video')} • Oflayn tayyor</div>
                    </div>
                </div>
                <button class="icon-btn" onclick="deleteLocalVideo('${escapeHtml(file.name)}')" title="O'chirish" style="color:#FF3B30;">
                    <i class="fa-solid fa-trash"></i>
                </button>
            </div>
        `).join('');
    } catch (e) {
        console.error('Error reading downloaded files:', e);
    }
}

function playLocalVideo(filePath) {
    if (window.AniRateNative && typeof window.AniRateNative.playDownloadedVideo === 'function') {
        window.AniRateNative.playDownloadedVideo(filePath);
    } else {
        const playerModal = document.getElementById('playerModal');
        const videoEl = document.getElementById('appVideoElement');
        videoEl.src = filePath;
        playerModal.style.display = 'flex';
        videoEl.play();
    }
}

function deleteLocalVideo(fileName) {
    if (confirm(`Rostdan ham "${fileName}" videosini o'chirmoqchimisiz?`)) {
        if (window.AniRateNative && typeof window.AniRateNative.deleteDownloadedFile === 'function') {
            window.AniRateNative.deleteDownloadedFile(fileName);
            showNativeToast("Video o'chirildi");
            setTimeout(refreshDownloadedFiles, 300);
        }
    }
}

function updateDownloadsBadge(count) {
    const badge = document.getElementById('headerDownloadsBadge');
    if (!badge) return;
    if (count !== undefined) {
        badge.textContent = count;
        badge.style.display = count > 0 ? 'block' : 'none';
        return;
    }
    if (window.AniRateNative && typeof window.AniRateNative.getDownloadedFiles === 'function') {
        try {
            const files = JSON.parse(window.AniRateNative.getDownloadedFiles() || '[]');
            badge.textContent = files.length;
            badge.style.display = files.length > 0 ? 'block' : 'none';
        } catch (e) {}
    }
}

// =========================================================================
// 6. SETTINGS & UTILS
// =========================================================================

function clearAppCache() {
    localStorage.clear();
    showNativeToast("Ilova keshi tozalandi!");
    setTimeout(() => {
        location.reload();
    }, 500);
}

function openTelegramChannel() {
    const url = "https://t.me/anirateuzrobot";
    if (window.AniRateNative && typeof window.AniRateNative.openTelegram === 'function') {
        window.AniRateNative.openTelegram(url);
    } else {
        window.open(url, '_blank');
    }
}

function openAdminPrompt() {
    const password = prompt("Admin parolini kiriting:");
    if (!password) return;
    if (window.AniRateNative && typeof window.AniRateNative.checkAdminPassword === 'function') {
        window.AniRateNative.checkAdminPassword(password);
    } else {
        showNativeToast("Admin rejimi faqat ilovada qo'llab-quvvatlanadi");
    }
}

function showOfflineBar() {
    const bar = document.getElementById('offlineBar');
    if (bar) bar.style.display = 'block';
}

function hideOfflineBar() {
    const bar = document.getElementById('offlineBar');
    if (bar) bar.style.display = 'none';
}

function showNativeToast(msg) {
    if (window.AniRateNative && typeof window.AniRateNative.showToast === 'function') {
        window.AniRateNative.showToast(msg);
    } else {
        alert(msg);
    }
}

function vibrateNative(ms = 20) {
    try {
        if (window.AniRateNative && typeof window.AniRateNative.vibrate === 'function') {
            window.AniRateNative.vibrate(ms);
        } else if (navigator.vibrate) {
            navigator.vibrate(ms);
        }
    } catch (e) {}
}

function createAnimeCardHtml(item) {
    return `
        <div class="anime-card" onclick="openAnimeDetail(${item.id})">
            <div class="anime-poster-box">
                <img src="${item.poster || 'img/icon-192.png'}" alt="${escapeHtml(item.title)}" class="anime-poster-img" loading="lazy">
                <div class="anime-card-badge">
                    <i class="fa-solid fa-star"></i> ${item.rating || '9.0'}
                </div>
                ${item.ep_count ? `<div class="anime-ep-badge">${item.ep_count} qism</div>` : ''}
            </div>
            <div class="anime-info-box">
                <div class="anime-title">${escapeHtml(item.title)}</div>
                <div class="anime-year">${item.year || ''} • ${escapeHtml(item.turi || 'Anime')}</div>
            </div>
        </div>
    `;
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
