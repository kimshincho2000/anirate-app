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

    // 5. Check and restore Admin state if logged in
    checkAdminState();

    // 6. Expose bridge for Android Native back button
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
    document.querySelectorAll('.mobile-nav-item, .nav-item').forEach(el => el.classList.remove('active'));
    const targetTab = document.getElementById(`tab-${viewName}`);
    if (targetTab) targetTab.classList.add('active');

    window.scrollTo({ top: 0, behavior: 'smooth' });

    // Haptic feedback
    vibrateNative(15);

    // Pause player and reset theater lights if leaving detail view
    if (viewName !== 'detail') {
        if (plyrInstance) {
            try { plyrInstance.pause(); } catch(e) {}
        }
        document.body.classList.remove('theater-lights-off');
        dismissNextEpOverlay();
        closePlayerModal();
    }

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
// 1. HOME VIEW LOGIC (EXACT ANIRATE.WWWZ.UZ DESIGN)
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
    // 1. Render Hero Swiper (Exact Website Hero Layout)
    const heroWrapper = document.getElementById('heroSwiperWrapper');
    if (heroWrapper && data.featured && data.featured.length > 0) {
        heroWrapper.innerHTML = data.featured.map(item => `
            <div class="swiper-slide hero-slide">
                <div class="hero-backdrop" style="background-image: url('${item.banner || item.poster}');"></div>
                <div class="hero-gradient-overlay"></div>
                <div class="hero-gradient-bottom"></div>
                <div class="hero-content-wrap">
                    <div class="hero-info">
                        <div class="hero-badges">
                            <span class="hero-pill hero-pill-gold">
                                <i class="fa-solid fa-fire"></i>
                                <span>Tavsiya etiladi</span>
                            </span>
                            <span class="hero-pill hero-pill-rating">
                                <i class="fa-solid fa-star"></i>
                                <span>${item.rating ? Number(item.rating).toFixed(1) : '9.0'}</span>
                            </span>
                            <span class="hero-pill hero-pill-status">
                                <i class="fa-solid fa-circle-check"></i>
                                <span>${escapeHtml(item.status || 'Tugallangan')}</span>
                            </span>
                            <span class="hero-pill">
                                <i class="fa-regular fa-calendar"></i>
                                <span>${escapeHtml(String(item.year || '2024'))}</span>
                            </span>
                        </div>
                        <h1 class="hero-title" style="font-size: 22px; line-height: 1.25; margin-bottom: 8px;">${escapeHtml(item.title)}</h1>
                        <div class="hero-meta-row" style="margin-bottom: 10px;">
                            <span class="hero-meta-item">
                                <i class="fa-solid fa-masks-theater"></i>
                                <span>${escapeHtml(item.genres || 'Anime')}</span>
                            </span>
                            <span class="hero-meta-item">
                                <i class="fa-solid fa-microphone-lines"></i>
                                <span>AniRate Dub</span>
                            </span>
                        </div>
                        <p class="hero-desc" style="display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-bottom: 16px; font-size: 12.5px; line-height: 1.5;">${escapeHtml(item.description || "Ushbu animeni o'zbek tilida yuqori sifatda tomosha qiling.")}</p>
                        <div class="hero-actions" style="display: flex; gap: 10px;">
                            <button class="btn-netflix-play" onclick="openAnimeDetail(${item.id})">
                                <i class="fa-solid fa-play"></i>
                                <span>Tomosha qilish</span>
                            </button>
                            <button class="btn-netflix-info" onclick="openAnimeDetail(${item.id})">
                                <i class="fa-solid fa-circle-info"></i>
                                <span>Batafsil</span>
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        `).join('');

        if (heroSwiperInstance) heroSwiperInstance.destroy();
        heroSwiperInstance = new Swiper('#heroSwiper', {
            slidesPerView: 1,
            spaceBetween: 0,
            loop: true,
            autoplay: { delay: 4500, disableOnInteraction: false },
            pagination: { el: '.hero-pagination', clickable: true },
            navigation: {
                nextEl: '.hero-arrow-next',
                prevEl: '.hero-arrow-prev'
            }
        });
    }

    // 2. Render Genre Quick Chips
    const genreScroll = document.getElementById('homeGenreScroll');
    if (genreScroll && data.genres && data.genres.length > 0) {
        let genreHtml = `<a class="genre-tag-pill ${currentGenreFilter === '' ? 'active' : ''}" onclick="filterByGenre('')"><i class="fa-solid fa-layer-group"></i> <span>Barchasi</span></a>`;
        data.genres.forEach(g => {
            const gName = typeof g === 'object' ? (g.name || '') : String(g);
            if (gName) {
                genreHtml += `<a class="genre-tag-pill ${currentGenreFilter === gName ? 'active' : ''}" onclick="filterByGenre('${escapeHtml(gName)}')"><i class="fa-solid fa-tag"></i> <span>${escapeHtml(gName)}</span></a>`;
            }
        });
        genreScroll.innerHTML = genreHtml;
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
    document.querySelectorAll('#homeGenreScroll .genre-tag-pill').forEach(el => {
        el.classList.toggle('active', el.textContent.trim().toLowerCase().includes((genre || 'barchasi').toLowerCase()));
    });
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
        grid.innerHTML = Array(6).fill('<div class="animedia-card skeleton" style="height: 220px;"></div>').join('');
    }

    try {
        const url = `${API_BASE}?action=catalog&page=${page}&per_page=18&type=${encodeURIComponent(type)}&sort=${encodeURIComponent(sort)}&genre=${encodeURIComponent(genre)}`;
        const res = await fetch(url);
        const json = await res.json();

        if (json.ok && json.data) {
            const items = json.data.items || [];
            if (!append) grid.innerHTML = '';

            if (items.length === 0 && !append) {
                grid.innerHTML = '<div class="empty-state" style="grid-column: 1/-1; padding: 40px; text-align: center;"><i class="fa-solid fa-film"></i><div>Hech qanday anime topilmadi</div></div>';
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
// 3. DETAIL VIEW LOGIC (EXACT anime.php DESIGN)
// =========================================================================

// =========================================================================
// 3. DETAIL VIEW & PLAYER LOGIC (100% 1:1 TO anime.php)
// =========================================================================

let plyrInstance = null;
let nextEpTimerInterval = null;
let currentEpNum = 1;
let seekAccumulator = 0;
let seekTimer = null;

function initPlyrPlayer() {
    const videoEl = document.getElementById('player');
    if (!videoEl || typeof Plyr === 'undefined') return;

    if (!plyrInstance) {
        try {
            plyrInstance = new Plyr('#player', {
                controls: [
                    'play-large',
                    'play',
                    'progress',
                    'current-time',
                    'duration',
                    'mute',
                    'volume',
                    'settings',
                    'pip',
                    'fullscreen'
                ],
                settings: ['speed'],
                speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
                seekTime: 10,
                keyboard: { focused: true, global: true },
                clickToPlay: true,
                hideControls: true,
                resetOnEnd: false,
                tooltips: { controls: true, seek: true }
            });

            videoEl.addEventListener('error', function() {
                const notice = document.getElementById('playerErrorNotice');
                if (notice) notice.style.display = 'flex';
            });

            // Ekran chetlariga 2 marta bosganda 10s oldinga/orqaga o'tkazish
            setupDoubleTapSeek();

            // Video tugaganda keyingi qism pop-up taymerini ishga tushirish
            plyrInstance.on('ended', function() {
                checkAndStartNextEpisode();
            });
        } catch (e) {
            console.error('Plyr init error:', e);
        }
    }
}

// 10 soniya o'tkazish va animatsiyali ko'rsatkich (Exact anime.php)
function performSeek(deltaSeconds, side) {
    if (!plyrInstance) return;

    const curTime = plyrInstance.currentTime || 0;
    const dur = plyrInstance.duration || 0;
    const newTime = Math.max(0, Math.min(dur, curTime + deltaSeconds));
    plyrInstance.currentTime = newTime;

    const overlay = document.getElementById(side === 'left' ? 'seekRippleLeft' : 'seekRippleRight');
    const textEl = document.getElementById(side === 'left' ? 'seekRippleLeftText' : 'seekRippleRightText');
    if (!overlay || !textEl) return;

    const otherOverlay = document.getElementById(side === 'left' ? 'seekRippleRight' : 'seekRippleLeft');
    if (otherOverlay) otherOverlay.classList.remove('active');

    seekAccumulator = (seekAccumulator && ((seekAccumulator > 0 && deltaSeconds > 0) || (seekAccumulator < 0 && deltaSeconds < 0)))
        ? (seekAccumulator + deltaSeconds)
        : deltaSeconds;

    textEl.textContent = (seekAccumulator > 0 ? '+' : '') + seekAccumulator + 's';
    overlay.classList.add('active');

    if (seekTimer) clearTimeout(seekTimer);
    seekTimer = setTimeout(function() {
        overlay.classList.remove('active');
        seekAccumulator = 0;
    }, 650);
}

function setupDoubleTapSeek() {
    const wrapper = document.getElementById('videoTheaterWrapper');
    if (!wrapper || wrapper._hasDoubleTap) return;
    wrapper._hasDoubleTap = true;

    // Mishka (sichqoncha) bilan ekran chetini 2 marta tez bosganda (Double Click)
    wrapper.addEventListener('dblclick', function(e) {
        if (e.target.closest('.plyr__controls') || e.target.closest('.player-theater-header') || e.target.closest('#playerNextEpOverlay')) {
            return;
        }
        const rect = wrapper.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const ratio = x / rect.width;

        if (ratio < 0.38) {
            e.preventDefault();
            e.stopPropagation();
            performSeek(-10, 'left');
        } else if (ratio > 0.62) {
            e.preventDefault();
            e.stopPropagation();
            performSeek(10, 'right');
        }
    });

    // Sensorli ekran (telefon) chetlarini 2 marta tez bosganda (Double Tap)
    let lastTapTime = 0;
    let lastTapX = 0;
    let lastTapY = 0;

    wrapper.addEventListener('touchend', function(e) {
        if (e.target.closest('.plyr__controls') || e.target.closest('.player-theater-header') || e.target.closest('#playerNextEpOverlay')) {
            return;
        }
        const touch = e.changedTouches ? e.changedTouches[0] : null;
        if (!touch) return;

        const rect = wrapper.getBoundingClientRect();
        const x = touch.clientX - rect.left;
        const y = touch.clientY - rect.top;
        const ratio = x / rect.width;
        const now = Date.now();
        const timeDiff = now - lastTapTime;
        const dist = Math.hypot(x - lastTapX, y - lastTapY);

        if (timeDiff < 320 && dist < 50) {
            if (ratio < 0.38) {
                e.preventDefault();
                performSeek(-10, 'left');
                if (plyrInstance && plyrInstance.paused) {
                    plyrInstance.play().catch(function(){});
                }
            } else if (ratio > 0.62) {
                e.preventDefault();
                performSeek(10, 'right');
                if (plyrInstance && plyrInstance.paused) {
                    plyrInstance.play().catch(function(){});
                }
            }
            lastTapTime = 0;
        } else {
            lastTapTime = now;
            lastTapX = x;
            lastTapY = y;
        }
    }, { passive: false });
}

function checkAndStartNextEpisode() {
    if (!currentAnimeData || !currentAnimeData.episodes) return;
    const episodes = currentAnimeData.episodes;
    const nextEp = episodes.find(e => parseInt(e.qism) === currentEpNum + 1);

    if (nextEp) {
        startNextEpCountdown(parseInt(nextEp.qism));
    }
}

function startNextEpCountdown(nextEpNumber) {
    const overlay = document.getElementById('playerNextEpOverlay');
    const numEl = document.getElementById('nextCountdownNum');
    const progressEl = document.getElementById('nextCircleProgress');
    const titleEl = document.getElementById('nextEpCountdownTitle');
    if (!overlay || !numEl) return;

    if (titleEl) titleEl.textContent = `${nextEpNumber}-qism boshlanmoqda`;
    overlay.style.display = 'flex';
    let count = 5;
    const total = 5;
    const circumference = 163.36;

    if (nextEpTimerInterval) clearInterval(nextEpTimerInterval);
    nextEpTimerInterval = setInterval(function() {
        count--;
        if (numEl) numEl.textContent = count;
        if (progressEl) {
            const offset = circumference * (1 - count / total);
            progressEl.style.strokeDashoffset = offset;
        }
        if (count <= 0) {
            clearInterval(nextEpTimerInterval);
            dismissNextEpOverlay();
            loadEpisode(nextEpNumber, true);
        }
    }, 1000);
}

function dismissNextEpOverlay() {
    if (nextEpTimerInterval) clearInterval(nextEpTimerInterval);
    const overlay = document.getElementById('playerNextEpOverlay');
    if (overlay) overlay.style.display = 'none';
}

function playNextEpisodeImmediately() {
    dismissNextEpOverlay();
    if (!currentAnimeData || !currentAnimeData.episodes) return;
    const episodes = currentAnimeData.episodes;
    const nextEp = episodes.find(e => parseInt(e.qism) === currentEpNum + 1);
    if (nextEp) {
        loadEpisode(parseInt(nextEp.qism), true);
    }
}

async function openAnimeDetail(animeId, initialEpNum = 1) {
    navigateTo('detail');
    dismissNextEpOverlay();

    // Reset indicator
    document.getElementById('detailTitle').textContent = 'Yuklanmoqda...';
    document.getElementById('detailPosterImg').src = 'img/icon-192.png';
    document.getElementById('episodesGrid').innerHTML = '<div class="skeleton" style="height: 48px; border-radius: 8px; grid-column: 1/-1;"></div>';
    document.getElementById('detailSimilarGrid').innerHTML = '';

    try {
        const res = await fetch(`${API_BASE}?action=anime&id=${animeId}`);
        const json = await res.json();

        if (json.ok && json.data) {
            const { anime, episodes, similar } = json.data;
            currentAnimeData = json.data;
            currentEpNum = initialEpNum;

            document.getElementById('detailTitle').textContent = anime.title || 'Anime';
            document.getElementById('detailPosterImg').src = anime.poster || 'img/icon-192.png';
            document.getElementById('detailGenres').textContent = anime.genres || 'Anime';
            document.getElementById('detailTypePill').innerHTML = `<i class="fa-solid fa-tv"></i> ${escapeHtml(anime.turi || 'Anime')}`;
            document.getElementById('detailYearPill').innerHTML = `<i class="fa-regular fa-calendar"></i> ${escapeHtml(String(anime.year || '2024'))}`;
            document.getElementById('detailRatingPill').innerHTML = `<i class="fa-solid fa-star"></i> ${anime.rating ? Number(anime.rating).toFixed(1) : '9.0'}`;
            document.getElementById('detailStatusPill').innerHTML = `<i class="fa-solid fa-circle-check"></i> ${escapeHtml(anime.status || 'Tugallangan')}`;
            document.getElementById('detailDescription').textContent = anime.description || "Ushbu anime uchun tavsif mavjud emas.";

            const totalEp = episodes ? episodes.length : 0;
            const plannedEp = anime.ep_count ? parseInt(anime.ep_count) : totalEp;
            const signalPercent = plannedEp > 0 ? Math.min(100, Math.round((totalEp / plannedEp) * 100)) : 100;

            document.getElementById('detailProgressText').textContent = `${signalPercent}% (${totalEp}${plannedEp ? '/' + plannedEp : ''} qism)`;
            document.getElementById('detailProgressBar').style.width = `${signalPercent}%`;
            document.getElementById('detailEpCountPill').textContent = `${totalEp} ta qism`;

            // Bot Links
            const botUsername = 'aniratebot';
            const botEpLink = `https://t.me/${botUsername}?start=ep${anime.id}n${currentEpNum}`;
            const botAllLink = `https://t.me/${botUsername}?start=all${anime.id}`;
            document.getElementById('detailTelegramBotLink').href = botEpLink;
            document.getElementById('playerErrorBotLink').href = botEpLink;
            document.getElementById('detailBotDownloadAllBtn').href = botAllLink;

            // Watchlist Tugmasi Holati
            updateWatchlistBtnState(anime.id);

            // Render Episodes Grid (Exact anime.php: .ep-num-btn EP {num})
            renderEpisodesGrid(episodes);

            // Initsializatsiya va 1-qismni ochish
            initPlyrPlayer();
            if (episodes && episodes.length > 0) {
                const targetEp = episodes.find(e => parseInt(e.qism) === currentEpNum) || episodes[0];
                loadEpisode(parseInt(targetEp.qism), false);
            }

            // Render Similar Anime
            const similarGrid = document.getElementById('detailSimilarGrid');
            if (similar && similar.length > 0) {
                similarGrid.innerHTML = similar.map(item => createAnimeCardHtml(item)).join('');
            } else {
                similarGrid.innerHTML = '<div style="color:var(--text-muted); font-size:13px;">O\'xshash animelar topilmadi</div>';
            }
        }
    } catch (err) {
        console.error('Anime detail fetch error:', err);
    }
}

function renderEpisodesGrid(episodes) {
    const grid = document.getElementById('episodesGrid');
    if (!episodes || episodes.length === 0) {
        grid.innerHTML = '<div style="text-align:center; padding:20px; color:var(--text-muted); grid-column:1/-1;">Hozircha qismlar yuklanmagan</div>';
        return;
    }

    grid.innerHTML = episodes.map(ep => {
        const epNum = parseInt(ep.qism);
        const isActive = (epNum === currentEpNum);
        return `
            <button type="button" class="ep-num-btn ${isActive ? 'active' : ''}" data-ep="${epNum}" onclick="loadEpisode(${epNum}, true)" style="display:flex; flex-direction:column; align-items:center; justify-content:center; padding:10px 4px; border-radius:8px; text-decoration:none; font-family:'Poppins', sans-serif; font-weight:700; font-size:13px; transition:all 0.2s; border:1px solid ${isActive ? 'var(--primary)' : 'var(--border-color)'}; background:${isActive ? 'linear-gradient(135deg, #E50914, #b81d24)' : 'rgba(255,255,255,0.04)'}; color:#fff; cursor:pointer;">
                <span style="font-size:8px; opacity:0.6; text-transform:uppercase;">EP</span>
                <span>${epNum}</span>
            </button>
        `;
    }).join('');
}

function loadEpisode(epNum, autoPlay = true) {
    if (!currentAnimeData || !currentAnimeData.episodes) return;
    const episodes = currentAnimeData.episodes;
    const anime = currentAnimeData.anime;
    const ep = episodes.find(e => parseInt(e.qism) === epNum);
    if (!ep) return;

    currentEpNum = epNum;
    dismissNextEpOverlay();

    // Sarlavhalarni yangilash
    document.getElementById('detailEpTabTitle').textContent = `${epNum}-qism`;
    document.getElementById('detailCurrentEpIndicator').textContent = `${epNum}-qism`;
    
    // Bot havolasini yangilash
    const botEpLink = `https://t.me/aniratebot?start=ep${anime.id}n${epNum}`;
    document.getElementById('detailTelegramBotLink').href = botEpLink;
    document.getElementById('playerErrorBotLink').href = botEpLink;

    // Grid tugmalarining active holatini yangilash
    const buttons = document.querySelectorAll('#episodesGrid .ep-num-btn');
    buttons.forEach(btn => {
        const bEp = parseInt(btn.getAttribute('data-ep'));
        if (bEp === epNum) {
            btn.classList.add('active');
            btn.style.border = '1px solid var(--primary)';
            btn.style.background = 'linear-gradient(135deg, #E50914, #b81d24)';
        } else {
            btn.classList.remove('active');
            btn.style.border = '1px solid var(--border-color)';
            btn.style.background = 'rgba(255,255,255,0.04)';
        }
    });

    const errorNotice = document.getElementById('playerErrorNotice');
    if (errorNotice) errorNotice.style.display = 'none';

    let videoUrl = ep.video_url || '';
    if (!videoUrl) {
        if (errorNotice) errorNotice.style.display = 'flex';
        return;
    }

    // Yuklab olish tugmasini sozlash
    const fileName = `${anime.title} — ${epNum}-qism.mp4`;
    const dlBtn = document.getElementById('downloadCurrentEpBtn');
    dlBtn.onclick = () => downloadCurrentAnimeEpisode(videoUrl, fileName);

    // Video manbasini Plyr orqali yangilash
    initPlyrPlayer();
    if (plyrInstance) {
        plyrInstance.source = {
            type: 'video',
            title: `${anime.title} — ${epNum}-qism`,
            sources: [
                {
                    src: videoUrl,
                    type: 'video/mp4'
                }
            ],
            poster: anime.poster || ''
        };

        if (autoPlay) {
            plyrInstance.play().catch(function(e) {
                console.log('Autoplay prevented:', e);
            });
        }
    } else {
        const videoEl = document.getElementById('player');
        if (videoEl) {
            videoEl.src = videoUrl;
            if (autoPlay) videoEl.play().catch(() => {});
        }
    }
}

function filterEpisodes() {
    const input = document.getElementById('epSearchInput');
    if (!input) return;
    const query = input.value.trim().toLowerCase();
    const buttons = document.querySelectorAll('#episodesGrid .ep-num-btn');
    buttons.forEach(function(btn) {
        const ep = btn.getAttribute('data-ep');
        if (query === '' || ep.indexOf(query) !== -1) {
            btn.style.display = 'flex';
        } else {
            btn.style.display = 'none';
        }
    });
}

function toggleTheaterMode() {
    const wrapper = document.getElementById('videoTheaterWrapper');
    const btn = document.getElementById('theaterModeBtn');
    if (wrapper) {
        wrapper.classList.toggle('theater-active');
        if (btn) btn.classList.toggle('active', wrapper.classList.contains('theater-active'));
    }
}

function toggleTheaterLights() {
    document.body.classList.toggle('theater-lights-off');
    const btn = document.getElementById('toggleLightsBtn');
    if (btn) btn.classList.toggle('active', document.body.classList.contains('theater-lights-off'));
}

function shareCurrentAnime() {
    if (!currentAnimeData || !currentAnimeData.anime) return;
    const anime = currentAnimeData.anime;
    const title = `${anime.title} — AniRate`;
    const url = `https://anirate.wwwz.uz/anime.php?id=${anime.id}&ep=${currentEpNum}`;

    if (navigator.share) {
        navigator.share({ title: title, url: url }).catch(() => {});
    } else {
        navigator.clipboard.writeText(url).then(() => {
            showNativeToast("Havola nusxalandi!");
        }).catch(() => {
            showNativeToast(url);
        });
    }
}

function downloadCurrentAnimeEpisode(url, filename) {
    if (!url) {
        showNativeToast("Yuklab olish havolasi topilmadi");
        return;
    }

    if (!url.startsWith('http://') && !url.startsWith('https://')) {
        url = 'https://anirate.wwwz.uz/' + (url.startsWith('/') ? url.slice(1) : url);
    }
    if (url.includes('episode-proxy.php')) {
        if (!url.includes('download=')) {
            url += (url.includes('?') ? '&' : '?') + 'download=1';
        }
        if (filename && !url.includes('name=')) {
            url += '&name=' + encodeURIComponent(filename);
        }
    }

    if (window.AniRateNative && typeof window.AniRateNative.downloadVideo === 'function') {
        window.AniRateNative.downloadVideo(url, filename);
    } else if (window.AndroidApp && typeof window.AndroidApp.downloadVideo === 'function') {
        window.AndroidApp.downloadVideo(url, filename);
    } else {
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.target = '_blank';
        document.body.appendChild(a);
        a.click();
        setTimeout(() => document.body.removeChild(a), 500);
    }
    showNativeToast(`Yuklab olish boshlandi: ${filename}`);
}

function updateWatchlistBtnState(animeId) {
    try {
        const list = JSON.parse(localStorage.getItem('animedia_watchlist') || '[]');
        const exists = list.some(item => parseInt(item.id) === parseInt(animeId));
        const btn = document.getElementById('detailWatchlistBtn');
        const text = document.getElementById('detailWatchlistText');
        if (btn && text) {
            if (exists) {
                btn.style.background = 'rgba(229, 9, 20, 0.2)';
                btn.style.color = '#ff4d4d';
                btn.style.borderColor = 'rgba(229, 9, 20, 0.5)';
                text.textContent = 'Saqlangan';
            } else {
                btn.style.background = 'rgba(255, 255, 255, 0.08)';
                btn.style.color = '#fff';
                btn.style.borderColor = 'var(--border-color)';
                text.textContent = 'Watchlist';
            }
        }
    } catch (e) {}
}

function toggleCurrentWatchlist() {
    if (!currentAnimeData || !currentAnimeData.anime) return;
    const anime = currentAnimeData.anime;
    try {
        let list = JSON.parse(localStorage.getItem('animedia_watchlist') || '[]');
        const idx = list.findIndex(item => parseInt(item.id) === parseInt(anime.id));
        if (idx > -1) {
            list.splice(idx, 1);
            showNativeToast(`"${anime.title}" saqlanganlardan o'chirildi`);
        } else {
            list.push({ id: anime.id, title: anime.title, poster: anime.poster, added_at: new Date().toISOString() });
            showNativeToast(`"${anime.title}" saqlanganlarga qo'shildi`);
        }
        localStorage.setItem('animedia_watchlist', JSON.stringify(list));
        updateWatchlistBtnState(anime.id);
        renderWatchlist();
    } catch (e) {
        console.error('Watchlist error:', e);
    }
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

function closePlayerModal() {
    const playerModal = document.getElementById('playerModal');
    const videoEl = document.getElementById('appVideoElement');
    if (videoEl) {
        videoEl.pause();
        videoEl.src = '';
    }
    if (playerModal) playerModal.style.display = 'none';
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

function updateAuthState() {
    const rawUser = localStorage.getItem('anirate_user');
    let user = null;
    try {
        if (rawUser) user = JSON.parse(rawUser);
    } catch (e) {}

    const isAdmin = localStorage.getItem('anirate_is_admin') === 'true' || (user && user.is_admin);

    const topbarAdminBtn = document.getElementById('topbarAdminBtn');
    const topbarUserIcon = document.getElementById('topbarUserIcon');
    const topbarUserText = document.getElementById('topbarUserText');
    const guestCard = document.getElementById('settingsProfileGuest');
    const userCard = document.getElementById('settingsProfileUser');
    const adminCard = document.getElementById('settingsProfileAdmin');

    if (window.AniRateNative && typeof window.AniRateNative.setAdmin === 'function') {
        window.AniRateNative.setAdmin(!!isAdmin);
    }

    if (isAdmin) {
        if (topbarAdminBtn) topbarAdminBtn.style.display = 'inline-flex';
        if (topbarUserIcon) {
            topbarUserIcon.className = 'fa-solid fa-crown';
            topbarUserIcon.style.color = '#FFD700';
        }
        if (topbarUserText) topbarUserText.textContent = 'Admin';
        if (guestCard) guestCard.style.display = 'none';
        if (userCard) userCard.style.display = 'none';
        if (adminCard) {
            adminCard.style.display = 'flex';
            const adminName = document.getElementById('settingsAdminDisplayName');
            const adminHandle = document.getElementById('settingsAdminHandle');
            if (adminName) adminName.textContent = (user && user.display_name) ? user.display_name : 'Administrator';
            if (adminHandle) adminHandle.textContent = (user && user.username) ? '@' + user.username : '@admin';
        }
    } else if (user) {
        if (topbarAdminBtn) topbarAdminBtn.style.display = 'none';
        if (topbarUserIcon) {
            topbarUserIcon.className = 'fa-solid fa-circle-user';
            topbarUserIcon.style.color = '#ffffff';
        }
        if (topbarUserText) topbarUserText.textContent = user.display_name || user.username || 'Profil';
        if (guestCard) guestCard.style.display = 'none';
        if (adminCard) adminCard.style.display = 'none';
        if (userCard) {
            userCard.style.display = 'flex';
            const userName = document.getElementById('settingsUserDisplayName');
            const userHandle = document.getElementById('settingsUserHandle');
            const userInit = document.getElementById('settingsUserInitial');
            if (userName) userName.textContent = user.display_name || user.username || 'Foydalanuvchi';
            if (userHandle) userHandle.textContent = user.username ? '@' + user.username : '';
            if (userInit) userInit.textContent = ((user.display_name || user.username || 'U')[0] || 'U').toUpperCase();
        }
    } else {
        if (topbarAdminBtn) topbarAdminBtn.style.display = 'none';
        if (topbarUserIcon) {
            topbarUserIcon.className = 'fa-solid fa-circle-user';
            topbarUserIcon.style.color = 'inherit';
        }
        if (topbarUserText) topbarUserText.textContent = 'Profil';
        if (guestCard) guestCard.style.display = 'flex';
        if (userCard) userCard.style.display = 'none';
        if (adminCard) adminCard.style.display = 'none';
    }
}

// Backward compatibility alias
const checkAdminState = updateAuthState;

function openLoginModal() {
    const modal = document.getElementById('profileLoginModal');
    const err = document.getElementById('accountLoginError');
    const userInput = document.getElementById('accountLoginInput');
    const passInput = document.getElementById('accountPasswordInput');
    if (err) err.style.display = 'none';
    if (userInput) userInput.value = '';
    if (passInput) passInput.value = '';
    if (modal) modal.style.display = 'flex';
    setTimeout(() => {
        if (userInput) userInput.focus();
    }, 200);
}

function closeLoginModal() {
    const modal = document.getElementById('profileLoginModal');
    if (modal) modal.style.display = 'none';
}

function handleTopbarUserClick() {
    const rawUser = localStorage.getItem('anirate_user');
    const isAdmin = localStorage.getItem('anirate_is_admin') === 'true';
    if (!rawUser && !isAdmin) {
        openLoginModal();
    } else {
        navigateTo('settings');
    }
}

async function handleAccountLoginSubmit(e) {
    e.preventDefault();
    const login = document.getElementById('accountLoginInput').value.trim();
    const password = document.getElementById('accountPasswordInput').value.trim();
    const errEl = document.getElementById('accountLoginError');
    const submitBtn = document.getElementById('btnAccountLoginSubmit');

    if (!login || !password) return;

    errEl.style.display = 'none';
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Tekshirilmoqda...';

    try {
        const formData = new FormData();
        formData.append('login', login);
        formData.append('password', password);

        const res = await fetch(`${API_BASE}?action=login`, {
            method: 'POST',
            body: formData
        });
        const json = await res.json();

        if (json.ok && json.data) {
            const userData = json.data.user || {};
            localStorage.setItem('anirate_user', JSON.stringify(userData));

            if (json.data.admin || userData.is_admin) {
                localStorage.setItem('anirate_is_admin', 'true');
                localStorage.setItem('anirate_admin_token', json.data.token || 'rofi');
                updateAuthState();
                closeLoginModal();
                showNativeToast("👑 Xush kelibsiz, Administrator!");
                vibrateNative(40);
            } else {
                localStorage.removeItem('anirate_is_admin');
                localStorage.removeItem('anirate_admin_token');
                updateAuthState();
                closeLoginModal();
                showNativeToast(`Xush kelibsiz, ${userData.display_name || userData.username}!`);
                vibrateNative(30);
            }
        } else {
            errEl.textContent = json.error || "Login yoki parol noto'g'ri!";
            errEl.style.display = 'block';
            vibrateNative(80);
        }
    } catch (err) {
        // Fallback for offline admin access
        if (password === '1234negr' || password === 'rofi') {
            const adminUser = { id: 0, username: login || 'admin', display_name: 'Administrator', is_admin: true };
            localStorage.setItem('anirate_user', JSON.stringify(adminUser));
            localStorage.setItem('anirate_is_admin', 'true');
            localStorage.setItem('anirate_admin_token', 'rofi');
            updateAuthState();
            closeLoginModal();
            showNativeToast("👑 Xush kelibsiz, Administrator!");
        } else {
            errEl.textContent = "Serverga ulanib bo'lmadi. Internetni tekshiring.";
            errEl.style.display = 'block';
        }
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = '<i class="fa-solid fa-right-to-bracket"></i> Kirish';
    }
}

function logoutAccount() {
    if (confirm("Akkauntdan chiqishni tasdiqlaysizmi?")) {
        localStorage.removeItem('anirate_user');
        localStorage.removeItem('anirate_is_admin');
        localStorage.removeItem('anirate_admin_token');
        if (window.AniRateNative && typeof window.AniRateNative.setAdmin === 'function') {
            window.AniRateNative.setAdmin(false);
        }
        updateAuthState();
        showNativeToast("Akkauntdan chiqildi");
    }
}

function openAdminPanel() {
    const token = localStorage.getItem('anirate_admin_token') || 'rofi';
    const adminUrl = `https://anirate.wwwz.uz/admin.php?admin_token=${token}`;
    window.location.href = adminUrl;
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
    const rating = item.rating ? Number(item.rating).toFixed(1) : '9.0';
    return `
        <div class="animedia-card" onclick="openAnimeDetail(${item.id})">
            <div class="card-poster-wrap">
                <img src="${item.poster || 'img/icon-192.png'}" alt="${escapeHtml(item.title)}" class="card-poster-img" loading="lazy">
                <div class="card-top-badges">
                    <span class="badge-rating-pill"><i class="fa-solid fa-star"></i> ${rating}</span>
                    ${item.ep_count ? `<span class="badge-ep-pill"><i class="fa-solid fa-tv"></i> ${item.ep_count}</span>` : ''}
                </div>
                <div class="card-hover-overlay"><div class="card-play-btn"><i class="fa-solid fa-play"></i></div></div>
            </div>
            <div class="card-content">
                <h3 class="card-title">${escapeHtml(item.title)}</h3>
                <div class="card-meta-row">
                    <span class="card-year">${item.year || ''}</span>
                    <span class="card-ep-badge">${escapeHtml(item.status || item.turi || 'Anime')}</span>
                </div>
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
