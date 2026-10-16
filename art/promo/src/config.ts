// Global switches for the promo build.

/**
 * Mount the music bed, `public/audio/music.wav` (`npm run music`), at the root of `Promo`. With
 * it on, the film's render (scripts/render-film.sh) first checks that the file is there and is
 * what the current timing and score make (scripts/check-music.mjs, which reads this line: keep
 * it `export const MUSIC = true | false`). Off renders a silent film, e.g. to try a cut before
 * the music is regenerated.
 */
export const MUSIC = true;
