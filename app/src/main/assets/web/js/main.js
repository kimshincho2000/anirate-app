/**
 * GoldAnime - Animedia TV JavaScript Suite
 * Handles Sidebar toggle, Theme switcher, Swiper carousels, Modals & Mobile Nav
 */

document.addEventListener('DOMContentLoaded', () => {
    // -------------------------------------------------------------------
    // 1. THEME SWITCHER (Dark / Light Mode)
    // -------------------------------------------------------------------
    const themeToggleBtn = document.getElementById('themeToggleBtn');
    const savedTheme = localStorage.getItem('animedia_theme') || 'dark';

    if (savedTheme === 'light') {
        document.documentElement.setAttribute('data-theme', 'light');
        if (themeToggleBtn) {
            themeToggleBtn.innerHTML = '<i class="fa-solid fa-moon"></i>';
        }
    } else {
        document.documentElement.setAttribute('data-theme', 'dark');
        if (themeToggleBtn) {
            themeToggleBtn.innerHTML = '<i class="fa-solid fa-sun"></i>';
        }
    }

    if (themeToggleBtn) {
        themeToggleBtn.addEventListener('click', () => {
            const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
            const nextTheme = currentTheme === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', nextTheme);
            localStorage.setItem('animedia_theme', nextTheme);
            themeToggleBtn.innerHTML = nextTheme === 'light' 
                ? '<i class="fa-solid fa-moon"></i>' 
                : '<i class="fa-solid fa-sun"></i>';
        });
    }

    // -------------------------------------------------------------------
    // 2. SIDEBAR TOGGLE (Desktop Collapse & Mobile Offcanvas Drawer)
    // -------------------------------------------------------------------
    const sidebar = document.getElementById('animediaSidebar');
    const edgeToggle = document.getElementById('sidebarEdgeToggle');
    const mobileMenuBtn = document.getElementById('mobileMenuBtn');
    const bottomNavMenuBtn = document.getElementById('bottomNavMenuBtn');
    const sidebarMobileCloseBtn = document.getElementById('sidebarMobileCloseBtn');
    const sidebarBackdrop = document.getElementById('sidebarBackdrop');

    // Desktop saved state
    function applyDesktopSidebarState() {
        if (!sidebar) return;
        if (window.innerWidth > 1024) {
            const isCollapsed = localStorage.getItem('animedia_sidebar_collapsed') === 'true';
            sidebar.classList.toggle('collapsed', isCollapsed);
            closeMobileSidebar();
        } else {
            sidebar.classList.remove('collapsed');
        }
    }
    applyDesktopSidebarState();

    if (edgeToggle && sidebar) {
        edgeToggle.addEventListener('click', (e) => {
            e.stopPropagation();
            sidebar.classList.toggle('collapsed');
            const collapsedNow = sidebar.classList.contains('collapsed');
            localStorage.setItem('animedia_sidebar_collapsed', collapsedNow ? 'true' : 'false');
        });
    }

    // Mobile Drawer
    function openMobileSidebar() {
        if (sidebar && sidebarBackdrop) {
            sidebar.classList.add('mobile-open');
            sidebarBackdrop.classList.add('open');
            document.body.style.overflow = 'hidden';
        }
    }

    function closeMobileSidebar() {
        if (sidebar && sidebarBackdrop) {
            sidebar.classList.remove('mobile-open');
            sidebarBackdrop.classList.remove('open');
            document.body.style.overflow = '';
        }
    }

    if (mobileMenuBtn) {
        mobileMenuBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            openMobileSidebar();
        });
    }

    if (bottomNavMenuBtn) {
        bottomNavMenuBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            openMobileSidebar();
        });
    }

    if (sidebarMobileCloseBtn) {
        sidebarMobileCloseBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            closeMobileSidebar();
        });
    }

    if (sidebarBackdrop) {
        sidebarBackdrop.addEventListener('click', closeMobileSidebar);
    }

    // Close mobile drawer when clicking navigation links inside sidebar
    if (sidebar) {
        const sidebarLinks = sidebar.querySelectorAll('.sidebar-link');
        sidebarLinks.forEach(link => {
            link.addEventListener('click', () => {
                if (window.innerWidth <= 1024) {
                    closeMobileSidebar();
                }
            });
        });
    }

    // Handle window resize
    window.addEventListener('resize', () => {
        applyDesktopSidebarState();
    });

    // Close on Escape key
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeMobileSidebar();
        }
    });

    // -------------------------------------------------------------------
    // 3. GENRE MODAL / DRAWER
    // -------------------------------------------------------------------
    const genreModal = document.getElementById('genreModal');
    const openGenreBtns = document.querySelectorAll('.trigger-genre-modal');
    const closeGenreBtn = document.getElementById('closeGenreModal');

    function openGenreModal() {
        if (genreModal) {
            genreModal.classList.add('open');
            document.body.style.overflow = 'hidden';
        }
    }

    function closeGenreModal() {
        if (genreModal) {
            genreModal.classList.remove('open');
            document.body.style.overflow = '';
        }
    }

    openGenreBtns.forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.preventDefault();
            openGenreModal();
        });
    });

    if (closeGenreBtn) {
        closeGenreBtn.addEventListener('click', closeGenreModal);
    }

    if (genreModal) {
        genreModal.addEventListener('click', (e) => {
            if (e.target === genreModal) {
                closeGenreModal();
            }
        });
    }

    // -------------------------------------------------------------------
    // 4. WATCHLIST (Local Storage Support)
    // -------------------------------------------------------------------
    window.toggleWatchlist = function(animeId, animeTitle, animePoster) {
        try {
            let list = JSON.parse(localStorage.getItem('animedia_watchlist') || '[]');
            const idx = list.findIndex(item => item.id == animeId);
            if (idx > -1) {
                list.splice(idx, 1);
                alert(`"${animeTitle}" saqlanganlardan o'chirildi.`);
            } else {
                list.push({ id: animeId, title: animeTitle, poster: animePoster, added_at: new Date().toISOString() });
                alert(`"${animeTitle}" saqlanganlarga (Watchlist) qo'shildi!`);
            }
            localStorage.setItem('animedia_watchlist', JSON.stringify(list));
            updateWatchlistUi();
        } catch (e) {
            console.error('Watchlist error:', e);
        }
    };

    function updateWatchlistUi() {
        const modalContainer = document.getElementById('watchlistItemsContainer');
        if (!modalContainer) return;
        
        try {
            const list = JSON.parse(localStorage.getItem('animedia_watchlist') || '[]');
            if (list.length === 0) {
                modalContainer.innerHTML = `
                    <div style="text-align:center;padding:40px 20px;color:var(--text-dim);">
                        <i class="fa-regular fa-bookmark" style="font-size:36px;margin-bottom:12px;display:block;"></i>
                        <p style="font-size:14px;">Hozircha saqlangan animelar yo'q.</p>
                    </div>`;
                return;
            }

            let html = '<div class="anime-cards-grid" style="grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 12px;">';
            list.forEach(item => {
                html += `
                    <a href="anime.php?id=${item.id}" class="animedia-card">
                        <div class="card-poster-wrap">
                            <img src="${item.poster}" alt="${item.title}" class="card-poster-img" loading="lazy" decoding="async">
                        </div>
                        <div class="card-content" style="padding:8px;">
                            <div class="card-title" style="font-size:12.5px;">${item.title}</div>
                        </div>
                    </a>`;
            });
            html += '</div>';
            modalContainer.innerHTML = html;
        } catch (e) {
            console.error(e);
        }
    }

    const openWatchlistBtns = document.querySelectorAll('.trigger-watchlist-modal');
    const watchlistModal = document.getElementById('watchlistModal');
    const closeWatchlistBtn = document.getElementById('closeWatchlistModal');

    openWatchlistBtns.forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.preventDefault();
            updateWatchlistUi();
            if (watchlistModal) {
                watchlistModal.classList.add('open');
                document.body.style.overflow = 'hidden';
            }
        });
    });

    if (closeWatchlistBtn && watchlistModal) {
        closeWatchlistBtn.addEventListener('click', () => {
            watchlistModal.classList.remove('open');
            document.body.style.overflow = '';
        });
        watchlistModal.addEventListener('click', (e) => {
            if (e.target === watchlistModal) {
                watchlistModal.classList.remove('open');
                document.body.style.overflow = '';
            }
        });
    }

    // -------------------------------------------------------------------
    // 5. SWIPER CAROUSELS INITIALIZATION
    // -------------------------------------------------------------------
    if (typeof Swiper !== 'undefined') {
        // Hero Carousel
        const heroEl = document.querySelector('.hero-swiper');
        if (heroEl) {
            const slideCount = heroEl.querySelectorAll('.swiper-slide').length;
            new Swiper('.hero-swiper', {
                loop: slideCount > 1,
                speed: 600,
                grabCursor: true,
                watchSlidesProgress: true,
                effect: 'fade',
                fadeEffect: {
                    crossFade: true
                },
                autoplay: slideCount > 1 ? {
                    delay: 5500,
                    disableOnInteraction: false,
                    pauseOnMouseEnter: true
                } : false,
                navigation: {
                    nextEl: '.hero-arrow-next',
                    prevEl: '.hero-arrow-prev'
                },
                pagination: {
                    el: '.hero-pagination',
                    clickable: true
                }
            });
        }

        // Latest Episodes Carousel
        const epEl = document.querySelector('.episodes-swiper');
        if (epEl) {
            new Swiper('.episodes-swiper', {
                slidesPerView: 1.25,
                spaceBetween: 10,
                grabCursor: true,
                watchSlidesProgress: true,
                resistanceRatio: 0.85,
                touchReleaseOnEdges: true,
                navigation: {
                    nextEl: '.ep-arrow-next',
                    prevEl: '.ep-arrow-prev'
                },
                breakpoints: {
                    380: {
                        slidesPerView: 1.4,
                        spaceBetween: 12
                    },
                    480: {
                        slidesPerView: 2.2,
                        spaceBetween: 14
                    },
                    640: {
                        slidesPerView: 2.7,
                        spaceBetween: 14
                    },
                    768: {
                        slidesPerView: 3.2,
                        spaceBetween: 16
                    },
                    1024: {
                        slidesPerView: 4.2,
                        spaceBetween: 18
                    },
                    1400: {
                        slidesPerView: 5.2,
                        spaceBetween: 18
                    }
                }
            });
        }
    }

    // -------------------------------------------------------------------
    // 6. MOBILE SEARCH BAR TOGGLE
    // -------------------------------------------------------------------
    const mobileSearchBtn = document.getElementById('mobileSearchBtn');
    const searchCloseMobile = document.getElementById('searchCloseMobile');
    const topbarSearchForm = document.getElementById('topbarSearchForm');
    const searchInput = topbarSearchForm ? topbarSearchForm.querySelector('.topbar-search-input') : null;

    if (mobileSearchBtn && topbarSearchForm) {
        mobileSearchBtn.addEventListener('click', (e) => {
            e.preventDefault();
            topbarSearchForm.classList.toggle('mobile-expanded');
            if (topbarSearchForm.classList.contains('mobile-expanded') && searchInput) {
                setTimeout(() => searchInput.focus(), 120);
            }
        });
    }

    if (searchCloseMobile && topbarSearchForm) {
        searchCloseMobile.addEventListener('click', (e) => {
            e.preventDefault();
            topbarSearchForm.classList.remove('mobile-expanded');
        });
    }

    // Close search on click outside
    document.addEventListener('click', (e) => {
        if (topbarSearchForm && topbarSearchForm.classList.contains('mobile-expanded')) {
            if (!topbarSearchForm.contains(e.target) && e.target !== mobileSearchBtn && !mobileSearchBtn?.contains(e.target)) {
                topbarSearchForm.classList.remove('mobile-expanded');
            }
        }
    });

    // ESC key closes any open modal or mobile search
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (topbarSearchForm && topbarSearchForm.classList.contains('mobile-expanded')) {
                topbarSearchForm.classList.remove('mobile-expanded');
            }
            if (genreModal && genreModal.classList.contains('open')) closeGenreModal();
            if (watchlistModal && watchlistModal.classList.contains('open')) {
                watchlistModal.classList.remove('open');
                document.body.style.overflow = '';
            }
            closeMobileSidebar();
        }
    });
});
