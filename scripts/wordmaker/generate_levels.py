import random
import os
from collections import Counter

class LevelGenerator:
    def __init__(self, word_list):
        self.word_list = [w.strip().upper() for w in word_list]
        self.word_index = {w: i for i, w in enumerate(self.word_list)}

    def generate_level(self, level):
        # Determine target difficulty index
        base_offset = (level - 800) * 45
        rows, cols = 11, 11
        
        # Pick a target wheel word (7-8 letters)
        wheel_candidates = [w for w in self.word_list[base_offset:base_offset+10000] if 7 <= len(w) <= 8]
        if not wheel_candidates:
            wheel_candidates = [w for w in self.word_list[max(0, len(self.word_list)-10000):] if 7 <= len(w) <= 8]
        if not wheel_candidates:
            wheel_candidates = [w for w in self.word_list if 7 <= len(w) <= 8]

        for _ in range(300):
            wheel_word = random.choice(wheel_candidates)
            wheel_counts = Counter(wheel_word)
            
            candidates = []
            for w in self.word_list:
                if 3 <= len(w) <= 8:
                    w_counts = Counter(w)
                    if all(w_counts[c] <= wheel_counts[c] for c in w_counts):
                        candidates.append(w)
            
            if len(candidates) < 6:
                continue
            
            # Difficulty scoring - favor words near base_offset
            def difficulty_score(w):
                idx = self.word_index.get(w, -1)
                # Penalize unknown or very common words for high levels
                if idx < 0 or idx < base_offset // 2: return 999999
                return abs(idx - base_offset)
            
            candidates.sort(key=difficulty_score)
            other_candidates = candidates[:50]
                
            for _ in range(200):
                grid = [[' ' for _ in range(cols)] for _ in range(rows)]
                
                # Determine mandatory words
                mandatory = [wheel_word]
                include_second_long = random.random() < 0.5
                if include_second_long:
                    long_candidates = [w for w in candidates if 6 <= len(w) <= 8 and w != wheel_word]
                    if long_candidates:
                        mandatory.append(random.choice(long_candidates[:10]))
                
                random.shuffle(other_candidates)
                words_to_try = mandatory + [w for w in other_candidates if w not in mandatory]
                words_to_try = words_to_try[:15]
                
                placed_words = []
                if self.place_words(grid, words_to_try, placed_words, rows, cols):
                    if level % 3 == 0:
                        self.add_disconnected_word(grid, candidates, placed_words, rows, cols)
                    
                    min_words = min(8, 5 + (level - 862) // 300)
                    # Check mandatory words were actually placed
                    if len(placed_words) >= min_words and all(w in placed_words for w in mandatory):
                        wheel = self.get_wheel_letters(placed_words)
                        distinct_letters = len(set(wheel))
                        total_letters = len(wheel)
                        
                        # Minimums scale with level
                        min_distinct = max(4, min(6, 4 + (level - 862) // 500))
                        min_total = max(5, min(8, 5 + (level - 862) // 400))
                        
                        if total_letters <= 8 and distinct_letters >= min_distinct and total_letters >= min_total:
                            return self.grid_to_string(grid)
        
        return "ERROR\nLEVEL"

    def get_wheel_letters(self, words):
        max_counts = {}
        for word in words:
            counts = {}
            for char in word:
                counts[char] = counts.get(char, 0) + 1
            for char, count in counts.items():
                max_counts[char] = max(max_counts.get(char, 0), count)
        
        wheel = []
        for char, count in max_counts.items():
            wheel.extend([char] * count)
        return wheel

    def place_words(self, grid, words, placed, rows, cols):
        if not words: return True
        # Sort words to place longest first
        words_sorted = sorted(words, key=len, reverse=True)
        first_word = words_sorted[0]
        
        if self.place_first_word(grid, first_word, rows, cols):
            placed.append(first_word)
            for i in range(1, len(words_sorted)):
                word = words_sorted[i]
                if self.try_place_intersecting(grid, word, placed, rows, cols):
                    placed.append(word)
                if len(placed) >= 12: break
            return len(placed) >= 4
        return False

    def place_first_word(self, grid, word, rows, cols):
        horizontal = random.choice([True, False])
        r = random.randint(rows // 4, rows // 2)
        c = random.randint(cols // 4, cols // 2)
        if self.can_place(grid, word, r, c, horizontal, rows, cols):
            self.do_place(grid, word, r, c, horizontal)
            return True
        return False

    def try_place_intersecting(self, grid, word, placed, rows, cols):
        possible_placements = []
        for r in range(rows):
            for c in range(cols):
                if self.can_place(grid, word, r, c, True, rows, cols) and self.intersects(grid, word, r, c, True):
                    possible_placements.append((r, c, True))
                if self.can_place(grid, word, r, c, False, rows, cols) and self.intersects(grid, word, r, c, False):
                    possible_placements.append((r, c, False))
        
        if possible_placements:
            r, c, horizontal = random.choice(possible_placements)
            self.do_place(grid, word, r, c, horizontal)
            return True
        return False

    def add_disconnected_word(self, grid, all_candidates, placed_words, rows, cols):
        available = [w for w in all_candidates if w not in placed_words]
        if not available: return
        
        # Calculate current bounds
        min_r, max_r = rows, -1
        min_c, max_c = cols, -1
        for r in range(rows):
            for c in range(cols):
                if grid[r][c] != ' ':
                    min_r = min(min_r, r)
                    max_r = max(max_r, r)
                    min_c = min(min_c, c)
                    max_c = max(max_c, c)
        
        if max_r == -1: return

        random.shuffle(available)
        for word in available[:20]:
            # Try placing above or below with exactly 1 row gap
            possible_placements = []
            
            # Above
            target_r = min_r - 2
            if target_r >= 0:
                for c in range(cols - len(word) + 1):
                    if self.can_place(grid, word, target_r, c, True, rows, cols, isolated=True):
                        possible_placements.append((target_r, c))
            
            # Below
            target_r = max_r + 2
            if target_r < rows:
                for c in range(cols - len(word) + 1):
                    if self.can_place(grid, word, target_r, c, True, rows, cols, isolated=True):
                        possible_placements.append((target_r, c))
            
            if possible_placements:
                r, c = random.choice(possible_placements)
                self.do_place(grid, word, r, c, True)
                placed_words.append(word)
                return

    def can_place(self, grid, word, r, c, horizontal, rows, cols, isolated=False):
        if horizontal:
            if c + len(word) > cols: return False
            for i in range(len(word)):
                char = grid[r][c + i]
                if char != ' ' and char != word[i]: return False
                if char == ' ':
                    if not self.check_neighbors(grid, r, c + i, horizontal, word[i], rows, cols): return False
            if c > 0 and grid[r][c - 1] != ' ': return False
            if c + len(word) < cols and grid[r][c + len(word)] != ' ': return False
        else:
            if r + len(word) > rows: return False
            for i in range(len(word)):
                char = grid[r + i][c]
                if char != ' ' and char != word[i]: return False
                if char == ' ':
                    if not self.check_neighbors(grid, r + i, c, horizontal, word[i], rows, cols): return False
            if r > 0 and grid[r - 1][c] != ' ': return False
            if r + len(word) < rows and grid[r + len(word)][c] != ' ': return False
            
        if isolated:
            for i in range(len(word)):
                curr_r = r if horizontal else r + i
                curr_c = c + i if horizontal else c
                if self.has_any_neighbor(grid, curr_r, curr_c, rows, cols): return False
        return True

    def check_neighbors(self, grid, r, c, horizontal, char, rows, cols):
        if horizontal:
            if r > 0 and grid[r - 1][c] != ' ': return False
            if r < rows - 1 and grid[r + 1][c] != ' ': return False
        else:
            if c > 0 and grid[r][c - 1] != ' ': return False
            if c < cols - 1 and grid[r][c + 1] != ' ': return False
        return True

    def has_any_neighbor(self, grid, r, c, rows, cols):
        dr = [-1, 1, 0, 0]
        dc = [0, 0, -1, 1]
        for i in range(4):
            nr, nc = r + dr[i], c + dc[i]
            if 0 <= nr < rows and 0 <= nc < cols and grid[nr][nc] != ' ':
                return True
        return False

    def intersects(self, grid, word, r, c, horizontal):
        for i in range(len(word)):
            curr_r = r if horizontal else r + i
            curr_c = c + i if horizontal else c
            if grid[curr_r][curr_c] == word[i]: return True
        return False

    def do_place(self, grid, word, r, c, horizontal):
        for i in range(len(word)):
            if horizontal: grid[r][c + i] = word[i]
            else: grid[r + i][c] = word[i]

    def grid_to_string(self, grid):
        # 1. Find the bounds of the actual letters
        rows = len(grid)
        cols = len(grid[0])
        min_r, max_r = rows, -1
        min_c, max_c = cols, -1
        
        for r in range(rows):
            for c in range(cols):
                if grid[r][c] != ' ':
                    min_r = min(min_r, r)
                    max_r = max(max_r, r)
                    min_c = min(min_c, c)
                    max_c = max(max_c, c)
        
        if max_r == -1: return ""
        
        # 2. Extract the subgrid
        trimmed_rows = []
        for r in range(min_r, max_r + 1):
            row_str = "".join(grid[r][min_c : max_c + 1])
            trimmed_rows.append(row_str)
        
        return "\n".join(trimmed_rows)

if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Load common words and unique them while preserving order
    with open(os.path.join(script_dir, "common_words_list.txt"), "r") as f:
        raw_words = [w.strip().upper() for w in f.readlines()]
        words = []
        seen = set()
        for w in raw_words:
            if w.isalpha() and w not in seen:
                words.append(w)
                seen.add(w)
    
    # Load bad words with "good Christian family" filtering:
    # - Exact matches always blocked (any length, e.g. DAMN, HELL, FRIGGING, NIGGAH, ASS, SEX)
    # - Substring blocked for severe slurs len>=4 (NIGGAH contains NIGGA, COONSKIN contains COON)
    #   but NOT for swear-adjacent euphemisms where innocent embedding is common:
    #   FRIG in REFRIGERATOR, HELL in SHELL, CRAP in SCRAP, DARN/DANG in DANGER, HECK in CHECK, etc.
    #   Those Christian words are exact-only.
    # - For 3-letter, only unambiguous blocked as substrings (FAG, BBW, XXX), while
    #   ASS/TIT/SEX/CUM allowed as substring because CLASS, TITLE, CUMIN are innocent.
    try:
        with open(os.path.join(script_dir, "bad-words.txt"), "r") as f:
            raw_bad = [w.strip().upper() for w in f.readlines() if w.strip()]
            bad_words = set(raw_bad)

            # Words that should be blocked ONLY as exact matches, not as substrings,
            # because they appear inside innocent words (HELL in SHELL, FRIG in REFRIGERATOR, etc)
            CHRISTIAN_EXACT_ONLY = {
                "FRIGGING","FRIGGIN","FRIGGED","FRIG","FRIKKIN","FRIKKING","FRICKING","FRICKIN","FRICK",
                "FECKING","FECKIN","FECK","FREAKING","FREAKIN",
                "DAMN","DAMNED","DAMNING","DAMNABLY","DAMNDEST","DAMMIT","GODDAMN","GODDAMNED","GODDAM",
                "HELL","HELLS","HELLISH",
                "CRAP","CRAPPY","CRAPPER",
                "PISS","PISSED","PISSER","PISSING","PISSY",
                "DARN","DARNED","DARNDEST","DANG","DANGED",
                "HECK","HECKLE","HECKLING","HECKLED","HECKLER",
                "BLOODY","BLOODILY",
            }
            COMMON_SENSE_INNOCENT_3 = {"ASS", "TIT", "SEX", "CUM"}

            bad_substrings = []
            for b in raw_bad:
                if not b.isalpha():
                    continue
                if b in CHRISTIAN_EXACT_ONLY:
                    continue  # exact-only
                if len(b) >= 4:
                    bad_substrings.append(b)
                elif len(b) == 3 and b not in COMMON_SENSE_INNOCENT_3:
                    bad_substrings.append(b)
    except FileNotFoundError:
        bad_words = set()
        bad_substrings = []

    def contains_bad_substring(word: str) -> bool:
        if word in bad_words:
            return True
        for bad in bad_substrings:
            if bad in word:
                return True
        return False

    words = [w for w in words if not contains_bad_substring(w)]
    
    generator = LevelGenerator(words)
    
    # Output path relative to scripts/wordmaker/
    output_dir = os.path.join(script_dir, "../../games/wordmaker/src/main/assets/levels")
    os.makedirs(output_dir, exist_ok=True)
    
    for level in range(2001, 8001):
        if level % 100 == 0:
            print(f"Generating level {level}...")
        grid_str = generator.generate_level(level)
        with open(os.path.join(output_dir, f"{level}.txt"), "w") as f:
            f.write(grid_str)
    print("Done!")
