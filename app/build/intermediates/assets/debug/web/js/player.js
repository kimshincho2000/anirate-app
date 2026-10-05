/**
 * GoldAnime Custom Video Player Engine (Ultra-Lightweight & Glassmorphism Edition)
 * Optimized for high performance, zero-lag scrubbing, RAF animation,
 * auto-resume playback, next episode prefetching, and seamless mobile touch gestures.
 */

(function () {
    'use strict';

    function initGoldPlayer() {
        const playerWrapper = document.getElementById('goldCustomPlayer');
        if (!playerWrapper) return;

        const video = document.getElementById('goldPlayerVideo');
        if (!video) return;

        // Container & Controls Elements
        const centerPlayWrap = document.getElementById('goldCenterPlayWrap');
        const centerPlayBtn = document.getElementById('goldCenterPlayBtn');
        const playBtn = document.getElementById('goldPlayBtn');
        const rewindBtn = document.getElementById('goldRewindBtn');
        const forwardBtn = document.getElementById('goldForwardBtn');
        const volumeBtn = document.getElementById('goldVolumeBtn');
        const volumeSlider = document.getElementById('goldVolumeSlider');
        const currentTimeEl = document.getElementById('goldCurrentTime');
        const durationEl = document.getElementById('goldDuration');
        const timelineWrap = document.getElementById('goldTimelineWrap');
        const timelineBuffer = document.getElementById('goldTimelineBuffer');
        const timelineProgress = document.getElementById('goldTimelineProgress');
        const timelineTooltip = document.getElementById('goldTimelineTooltip');
        const speedBtn = document.getElementById('goldSpeedBtn');
        const speedMenu = document.getElementById('goldSpeedMenu');
        const speedOpts = document.querySelectorAll('.gold-speed-opt');
        const pipBtn = document.getElementById('goldPipBtn');
        const fullscreenBtn = document.getElementById('goldFullscreenBtn');
        const spinner = document.getElementById('goldPlayerSpinner');
        const rippleLeft = document.getElementById('goldRippleLeft');
        const rippleRight = document.getElementById('goldRippleRight');

        // Theater & Lights Off Elements
        const toggleLightsBtn = document.getElementById('toggleLightsBtn');
        const lightsOverlay = document.getElementById('theaterLightsOverlay');
        const playerMainArea = document.querySelector('.player-main-area');
        const theaterModeBtn = document.getElementById('theaterModeBtn');
        const playerPageGrid = document.querySelector('.player-page-grid');

        // Source Switcher Tabs
        const sourceTabs = document.querySelectorAll('.player-tab-btn[data-player]');
        const customPlayerContainer = document.getElementById('customPlayerContainer');
        const embedPlayerContainer = document.getElementById('embedPlayerContainer');

        // Metadata & Storage Attributes
        const animeId = playerWrapper.getAttribute('data-anime-id') || '0';
        const epNum = playerWrapper.getAttribute('data-ep') || '1';
        const nextUrl = playerWrapper.getAttribute('data-next-url') || '';
        const storageKey = `gold_watch_pos_${animeId}_${epNum}`;

        let isSeeking = false;
        let seekTargetTime = 0;
        let lastRafId = null;
        let hideTimer = null;
        let hasPrefetchedNext = false;
        let hasRestoredPosition = false;

        // -------------------------------------------------------------
        // 1. Time Formatting Helper (HH:MM:SS or MM:SS) - Ultra-fast
        // -------------------------------------------------------------
        function formatTime(seconds) {
            if (!seconds || isNaN(seconds) || seconds < 0) return '00:00';
            const s = Math.floor(seconds % 60);
            const m = Math.floor((seconds / 60) % 60);
            const h = Math.floor(seconds / 3600);
            const sStr = s < 10 ? '0' + s : s;
            const mStr = m < 10 ? '0' + m : m;
            return h > 0 ? `${h}:${mStr}:${sStr}` : `${mStr}:${sStr}`;
        }

        // -------------------------------------------------------------
        // 2. Play / Pause Logic
        // -------------------------------------------------------------
        function togglePlay() {
            if (video.paused || video.ended) {
                const playPromise = video.play();
                if (playPromise !== undefined) {
                    playPromise.catch(() => {});
                }
            } else {
                video.pause();
            }
        }

        function updatePlayState() {
            const isPaused = video.paused;
            if (playBtn) {
                playBtn.innerHTML = isPaused
                    ? '<i class="fa-solid fa-play"></i>'
                    : '<i class="fa-solid fa-pause"></i>';
            }
            if (centerPlayWrap) {
                centerPlayWrap.classList.toggle('hidden', !isPaused);
            }
        }

        if (playBtn) playBtn.addEventListener('click', togglePlay);
        if (centerPlayBtn) centerPlayBtn.addEventListener('click', togglePlay);
        video.addEventListener('play', updatePlayState);
        video.addEventListener('pause', updatePlayState);

        // -------------------------------------------------------------
        // 3. Skip Relative (+/- 10s) with Ripple Animation
        // -------------------------------------------------------------
        function seekRelative(seconds) {
            const cur = isSeeking ? seekTargetTime : video.currentTime;
            const target = Math.max(0, Math.min(video.duration || 0, cur + seconds));
            video.currentTime = target;

            if (seconds > 0 && rippleRight) {
                rippleRight.classList.remove('animate');
                void rippleRight.offsetWidth;
                rippleRight.classList.add('animate');
            } else if (seconds < 0 && rippleLeft) {
                rippleLeft.classList.remove('animate');
                void rippleLeft.offsetWidth;
                rippleLeft.classList.add('animate');
            }
            renderProgress(target);
        }

        if (rewindBtn) rewindBtn.addEventListener('click', () => seekRelative(-10));
        if (forwardBtn) forwardBtn.addEventListener('click', () => seekRelative(10));

        // -------------------------------------------------------------
        // 4. Video Tap / Double-Tap Logic (Mobile & Desktop)
        // -------------------------------------------------------------
        let lastTap = 0;
        video.addEventListener('click', (e) => {
            const now = Date.now();
            const rect = video.getBoundingClientRect();
            const clickX = e.clientX - rect.left;

            if (now - lastTap < 280) {
                // Double tap: Skip forward/backward
                if (clickX > rect.width * 0.65) {
                    seekRelative(10);
                } else if (clickX < rect.width * 0.35) {
                    seekRelative(-10);
                }
                lastTap = 0;
                return;
            }
            lastTap = now;

            // If controls are hidden, wake them up first
            if (playerWrapper.classList.contains('controls-hidden')) {
                resetControlTimer();
                return;
            }

            togglePlay();
            resetControlTimer();
        });

        // -------------------------------------------------------------
        // 5. High-Performance Progress & Buffer Rendering (RAF)
        // -------------------------------------------------------------
        function renderProgress(time) {
            if (!video.duration) return;
            const duration = video.duration;
            const percent = Math.max(0, Math.min(100, (time / duration) * 100));

            if (timelineProgress) timelineProgress.style.width = percent + '%';
            if (currentTimeEl) currentTimeEl.textContent = formatTime(time);
        }

        function updateBuffer() {
            if (!video.duration || !timelineBuffer || video.buffered.length === 0) return;
            try {
                const duration = video.duration;
                const bufferedEnd = video.buffered.end(video.buffered.length - 1);
                const bufferPercent = Math.min(100, (bufferedEnd / duration) * 100);
                timelineBuffer.style.width = bufferPercent + '%';
            } catch (_) {}
        }

        video.addEventListener('timeupdate', () => {
            if (isSeeking) return; // Don't interrupt user during drag scrubbing

            if (lastRafId) cancelAnimationFrame(lastRafId);
            lastRafId = requestAnimationFrame(() => {
                renderProgress(video.currentTime);
                updateBuffer();

                // Save playback position periodically (every 4s)
                if (video.currentTime > 5 && !video.paused) {
                    try {
                        localStorage.setItem(storageKey, Math.floor(video.currentTime).toString());
                    } catch (_) {}
                }

                // Smart Next-Episode Prefetching at 80% progress
                if (!hasPrefetchedNext && nextUrl && video.duration > 60 && (video.currentTime / video.duration) >= 0.8) {
                    hasPrefetchedNext = true;
                    try {
                        const link = document.createElement('link');
                        link.rel = 'prefetch';
                        link.href = nextUrl;
                        document.head.appendChild(link);
                    } catch (_) {}
                }
            });
        });

        video.addEventListener('loadedmetadata', () => {
            if (durationEl) durationEl.textContent = formatTime(video.duration);
            updateBuffer();

            // Auto-resume from previous saved position
            if (!hasRestoredPosition && animeId !== '0') {
                try {
                    const saved = localStorage.getItem(storageKey);
                    if (saved) {
                        const savedTime = parseFloat(saved);
                        if (savedTime > 10 && savedTime < (video.duration - 20)) {
                            video.currentTime = savedTime;
                            renderProgress(savedTime);
                        }
                    }
                } catch (_) {}
                hasRestoredPosition = true;
            }
        });

        video.addEventListener('progress', updateBuffer);

        // Auto-navigate to Next Episode on end if available
        video.addEventListener('ended', () => {
            if (nextUrl) {
                setTimeout(() => {
                    window.location.href = nextUrl;
                }, 1200);
            }
        });

        // -------------------------------------------------------------
        // 6. Zero-Lag Timeline Seeker Drag / Scrubbing
        // (Only sets video.currentTime ONCE on release - stops Range abort floods!)
        // -------------------------------------------------------------
        function calculateSeekPos(e) {
            if (!timelineWrap) return 0;
            const rect = timelineWrap.getBoundingClientRect();
            const clientX = e.clientX !== undefined ? e.clientX : (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
            return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
        }

        function updateSeekVisual(pos) {
            if (!video.duration) return;
            seekTargetTime = pos * video.duration;
            if (timelineProgress) timelineProgress.style.width = (pos * 100) + '%';
            if (currentTimeEl) currentTimeEl.textContent = formatTime(seekTargetTime);
            if (timelineTooltip) {
                timelineTooltip.style.left = (pos * 100) + '%';
                timelineTooltip.textContent = formatTime(seekTargetTime);
            }
        }

        function onSeekStart(e) {
            if (!video.duration || !timelineWrap) return;
            isSeeking = true;
            timelineWrap.classList.add('seeking');
            const pos = calculateSeekPos(e);
            updateSeekVisual(pos);
            resetControlTimer();
        }

        function onSeekMove(e) {
            if (!isSeeking) {
                // Hover tooltip preview when not dragging
                if (timelineWrap && timelineTooltip && video.duration) {
                    const rect = timelineWrap.getBoundingClientRect();
                    const clientX = e.clientX;
                    if (clientX >= rect.left && clientX <= rect.right) {
                        const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
                        timelineTooltip.style.left = (pos * 100) + '%';
                        timelineTooltip.textContent = formatTime(pos * video.duration);
                    }
                }
                return;
            }
            const pos = calculateSeekPos(e);
            updateSeekVisual(pos);
        }

        function onSeekEnd() {
            if (!isSeeking) return;
            isSeeking = false;
            if (timelineWrap) timelineWrap.classList.remove('seeking');

            // Apply target currentTime ONCE to prevent spamming the stream server
            if (video.duration && !isNaN(seekTargetTime)) {
                video.currentTime = seekTargetTime;
            }
        }

        if (timelineWrap) {
            timelineWrap.addEventListener('mousedown', onSeekStart);
            window.addEventListener('mousemove', onSeekMove, { passive: true });
            window.addEventListener('mouseup', onSeekEnd);

            timelineWrap.addEventListener('touchstart', onSeekStart, { passive: true });
            window.addEventListener('touchmove', onSeekMove, { passive: true });
            window.addEventListener('touchend', onSeekEnd);
        }

        // -------------------------------------------------------------
        // 7. Volume & Mute Controls
        // -------------------------------------------------------------
        function updateVolumeIcon(vol, muted) {
            if (!volumeBtn) return;
            if (muted || vol === 0) {
                volumeBtn.innerHTML = '<i class="fa-solid fa-volume-xmark"></i>';
            } else if (vol < 0.5) {
                volumeBtn.innerHTML = '<i class="fa-solid fa-volume-low"></i>';
            } else {
                volumeBtn.innerHTML = '<i class="fa-solid fa-volume-high"></i>';
            }
        }

        if (volumeSlider) {
            volumeSlider.addEventListener('input', (e) => {
                const val = parseFloat(e.target.value);
                video.volume = val;
                video.muted = (val === 0);
                updateVolumeIcon(val, video.muted);
            });
        }

        if (volumeBtn) {
            volumeBtn.addEventListener('click', () => {
                video.muted = !video.muted;
                if (volumeSlider) {
                    volumeSlider.value = video.muted ? 0 : video.volume;
                }
                updateVolumeIcon(video.volume, video.muted);
            });
        }

        // -------------------------------------------------------------
        // 8. Playback Speed Selector (0.5x - 2.0x)
        // -------------------------------------------------------------
        if (speedBtn && speedMenu) {
            speedBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                speedMenu.classList.toggle('open');
            });

            speedOpts.forEach(opt => {
                opt.addEventListener('click', () => {
                    const speed = parseFloat(opt.dataset.speed);
                    video.playbackRate = speed;
                    speedBtn.textContent = speed === 1 ? '1.0x' : speed + 'x';
                    speedOpts.forEach(o => o.classList.remove('active'));
                    opt.classList.add('active');
                    speedMenu.classList.remove('open');
                });
            });

            document.addEventListener('click', (e) => {
                if (!speedMenu.contains(e.target) && e.target !== speedBtn) {
                    speedMenu.classList.remove('open');
                }
            });
        }

        // -------------------------------------------------------------
        // 9. Picture-in-Picture (PiP)
        // -------------------------------------------------------------
        if (pipBtn) {
            if (document.pictureInPictureEnabled) {
                pipBtn.addEventListener('click', async () => {
                    try {
                        if (document.pictureInPictureElement) {
                            await document.exitPictureInPicture();
                        } else {
                            await video.requestPictureInPicture();
                        }
                    } catch (_) {}
                });
            } else {
                pipBtn.style.display = 'none';
            }
        }

        // -------------------------------------------------------------
        // 10. Fullscreen Toggle (Supports Desktop, Android & iOS Safari)
        // -------------------------------------------------------------
        function toggleFullscreen() {
            if (!document.fullscreenElement && !document.webkitFullscreenElement) {
                if (playerWrapper.requestFullscreen) {
                    playerWrapper.requestFullscreen().catch(() => {
                        if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
                    });
                } else if (playerWrapper.webkitRequestFullscreen) {
                    playerWrapper.webkitRequestFullscreen();
                } else if (video.webkitEnterFullscreen) {
                    video.webkitEnterFullscreen();
                }
            } else {
                if (document.exitFullscreen) {
                    document.exitFullscreen();
                } else if (document.webkitExitFullscreen) {
                    document.webkitExitFullscreen();
                }
            }
        }

        if (fullscreenBtn) fullscreenBtn.addEventListener('click', toggleFullscreen);
        video.addEventListener('dblclick', toggleFullscreen);

        ['fullscreenchange', 'webkitfullscreenchange'].forEach(evt => {
            document.addEventListener(evt, () => {
                const isFull = !!(document.fullscreenElement || document.webkitFullscreenElement);
                if (fullscreenBtn) {
                    fullscreenBtn.innerHTML = isFull
                        ? '<i class="fa-solid fa-compress"></i>'
                        : '<i class="fa-solid fa-expand"></i>';
                }
            });
        });

        // -------------------------------------------------------------
        // 11. Smooth Controls Auto-Hide (Debounced 3.2s timer)
        // -------------------------------------------------------------
        function resetControlTimer() {
            playerWrapper.classList.remove('controls-hidden');
            clearTimeout(hideTimer);
            if (!video.paused && !isSeeking) {
                hideTimer = setTimeout(() => {
                    if (!video.paused && !isSeeking) {
                        playerWrapper.classList.add('controls-hidden');
                        if (speedMenu) speedMenu.classList.remove('open');
                    }
                }, 3200);
            }
        }

        playerWrapper.addEventListener('mousemove', resetControlTimer, { passive: true });
        playerWrapper.addEventListener('touchstart', resetControlTimer, { passive: true });
        playerWrapper.addEventListener('mouseleave', () => {
            if (!video.paused && !isSeeking) {
                playerWrapper.classList.add('controls-hidden');
            }
        });

        // -------------------------------------------------------------
        // 12. Buffering & Loading Indicator
        // -------------------------------------------------------------
        video.addEventListener('waiting', () => {
            if (spinner) spinner.style.display = 'block';
        });
        video.addEventListener('playing', () => {
            if (spinner) spinner.style.display = 'none';
        });
        video.addEventListener('canplay', () => {
            if (spinner) spinner.style.display = 'none';
        });

        // -------------------------------------------------------------
        // 13. Global Keyboard Shortcuts
        // -------------------------------------------------------------
        document.addEventListener('keydown', (e) => {
            const activeTag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
            if (activeTag === 'input' || activeTag === 'textarea' || document.activeElement.isContentEditable) {
                return;
            }

            switch (e.code) {
                case 'Space':
                case 'KeyK':
                    e.preventDefault();
                    togglePlay();
                    resetControlTimer();
                    break;
                case 'KeyJ':
                case 'ArrowLeft':
                    e.preventDefault();
                    seekRelative(-10);
                    resetControlTimer();
                    break;
                case 'KeyL':
                case 'ArrowRight':
                    e.preventDefault();
                    seekRelative(10);
                    resetControlTimer();
                    break;
                case 'ArrowUp':
                    e.preventDefault();
                    video.volume = Math.min(1, video.volume + 0.1);
                    if (volumeSlider) volumeSlider.value = video.volume;
                    updateVolumeIcon(video.volume, video.muted);
                    resetControlTimer();
                    break;
                case 'ArrowDown':
                    e.preventDefault();
                    video.volume = Math.max(0, video.volume - 0.1);
                    if (volumeSlider) volumeSlider.value = video.volume;
                    updateVolumeIcon(video.volume, video.muted);
                    resetControlTimer();
                    break;
                case 'KeyM':
                    e.preventDefault();
                    video.muted = !video.muted;
                    if (volumeSlider) volumeSlider.value = video.muted ? 0 : video.volume;
                    updateVolumeIcon(video.volume, video.muted);
                    break;
                case 'KeyF':
                    e.preventDefault();
                    toggleFullscreen();
                    break;
                case 'KeyT':
                    e.preventDefault();
                    if (theaterModeBtn) theaterModeBtn.click();
                    break;
                case 'Digit0':
                case 'Digit1':
                case 'Digit2':
                case 'Digit3':
                case 'Digit4':
                case 'Digit5':
                case 'Digit6':
                case 'Digit7':
                case 'Digit8':
                case 'Digit9':
                    if (video.duration) {
                        const frac = parseInt(e.code.replace('Digit', ''), 10) / 10;
                        video.currentTime = frac * video.duration;
                        renderProgress(video.currentTime);
                        resetControlTimer();
                    }
                    break;
            }
        });

        // -------------------------------------------------------------
        // 14. Theater Mode & Lights Off Focus Mode
        // -------------------------------------------------------------
        if (theaterModeBtn && playerPageGrid) {
            theaterModeBtn.addEventListener('click', () => {
                playerPageGrid.classList.toggle('theater-active');
                const isActive = playerPageGrid.classList.contains('theater-active');
                theaterModeBtn.classList.toggle('active', isActive);
                theaterModeBtn.innerHTML = isActive
                    ? '<i class="fa-solid fa-compress"></i> <span>Oddiy rejim</span>'
                    : '<i class="fa-solid fa-expand"></i> <span>Teatr rejimi</span>';
            });
        }

        if (toggleLightsBtn && lightsOverlay && playerMainArea) {
            toggleLightsBtn.addEventListener('click', () => {
                lightsOverlay.classList.toggle('active');
                playerMainArea.classList.toggle('lights-on-top');
                const isOn = lightsOverlay.classList.contains('active');
                toggleLightsBtn.classList.toggle('active', isOn);
                toggleLightsBtn.innerHTML = isOn
                    ? '<i class="fa-regular fa-lightbulb"></i> <span>Chiroqni yoqish</span>'
                    : '<i class="fa-solid fa-lightbulb"></i> <span>Chiroqni o\'chirish</span>';
            });

            lightsOverlay.addEventListener('click', () => {
                lightsOverlay.classList.remove('active');
                playerMainArea.classList.remove('lights-on-top');
                toggleLightsBtn.classList.remove('active');
                toggleLightsBtn.innerHTML = '<i class="fa-solid fa-lightbulb"></i> <span>Chiroqni o\'chirish</span>';
            });
        }

        // -------------------------------------------------------------
        // 15. Source Switcher (Server 1 vs Server 2)
        // -------------------------------------------------------------
        sourceTabs.forEach(tab => {
            tab.addEventListener('click', () => {
                const playerType = tab.dataset.player;
                sourceTabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');

                if (playerType === 'custom') {
                    if (customPlayerContainer) customPlayerContainer.style.display = 'block';
                    if (embedPlayerContainer) embedPlayerContainer.style.display = 'none';
                } else {
                    if (!video.paused) video.pause();
                    if (customPlayerContainer) customPlayerContainer.style.display = 'none';
                    if (embedPlayerContainer) embedPlayerContainer.style.display = 'block';
                }
            });
        });

        // -------------------------------------------------------------
        // 16. Auto-Fallback on Video Error to Server 2
        // -------------------------------------------------------------
        video.addEventListener('error', () => {
            if (spinner) spinner.style.display = 'none';
            const embedTab = document.querySelector('.player-tab-btn[data-player="embed"]');
            if (embedTab && embedPlayerContainer) {
                embedTab.click();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initGoldPlayer);
    } else {
        initGoldPlayer();
    }
})();
