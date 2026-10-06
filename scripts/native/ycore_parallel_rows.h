#pragma once

#include <algorithm>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#if defined(__ANDROID__) || defined(__linux__)
#include <pthread.h>
#endif

namespace ycore_parallel {

/**
 * A few persistent threads that split one row range per call.
 *
 * Per-pixel frame work (HDR tone mapping) has to fit one frame interval, which a single phone
 * core cannot do for 1080p. Starting threads per frame would churn several thread creations every
 * frame, so the helpers live as long as the decoder that owns them. The calling thread takes the
 * first band itself, so `extra_threads` is the number of helpers beyond the caller.
 *
 * run() returns only after every band has finished. It is meant for one caller at a time, which
 * the owning decoder guarantees; the work must not throw.
 */
class RowWorkers {
public:
    /** Throws std::system_error when a helper cannot start; no helper is left running then. */
    explicit RowWorkers(int extra_threads, const char* thread_name = "YCoreRows") {
        const int count = std::max(extra_threads, 0);
        try {
            threads_.reserve(static_cast<size_t>(count));
            for (int index = 0; index < count; ++index) {
                threads_.emplace_back([this, index, thread_name] {
                    name_current_thread(thread_name);
                    loop(index + 1);
                });
            }
        } catch (...) {
            stop();
            throw;
        }
    }

    ~RowWorkers() {
        stop();
    }

    RowWorkers(const RowWorkers&) = delete;
    RowWorkers& operator=(const RowWorkers&) = delete;

    int bands() const {
        return static_cast<int>(threads_.size()) + 1;
    }

    /** Calls work(first_row, end_row) on disjoint bands covering [0, rows). */
    void run(int rows, const std::function<void(int, int)>& work) {
        if (rows <= 0) return;
        const int bands = std::min(this->bands(), rows);
        if (bands == 1) {
            work(0, rows);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            work_ = &work;
            rows_ = rows;
            bands_ = bands;
            pending_ = bands - 1;
            ++generation_;
        }
        wake_.notify_all();
        work(0, band_start(1, rows, bands));
        std::unique_lock<std::mutex> lock(mutex_);
        done_.wait(lock, [this] { return pending_ == 0; });
        work_ = nullptr;
    }

private:
    static int band_start(int band, int rows, int bands) {
        return static_cast<int>(static_cast<int64_t>(rows) * band / bands);
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
        }
        wake_.notify_all();
        for (std::thread& thread : threads_) {
            if (thread.joinable()) thread.join();
        }
        threads_.clear();
    }

    static void name_current_thread(const char* name) {
#if defined(__ANDROID__) || defined(__linux__)
        // Thread names reach tombstones, which is how a native crash is attributed to its owner.
        if (name) pthread_setname_np(pthread_self(), name);
#else
        (void)name;
#endif
    }

    void loop(int band) {
        uint64_t seen = 0;
        std::unique_lock<std::mutex> lock(mutex_);
        while (true) {
            wake_.wait(lock, [this, &seen] { return stopping_ || generation_ != seen; });
            if (stopping_) return;
            seen = generation_;
            // A short frame uses fewer bands than there are helpers; the rest sit this one out.
            if (band >= bands_) continue;
            const std::function<void(int, int)>* work = work_;
            const int rows = rows_;
            const int bands = bands_;
            lock.unlock();
            (*work)(band_start(band, rows, bands), band_start(band + 1, rows, bands));
            lock.lock();
            if (--pending_ == 0) done_.notify_one();
        }
    }

    std::vector<std::thread> threads_;
    std::mutex mutex_;
    std::condition_variable wake_;
    std::condition_variable done_;
    const std::function<void(int, int)>* work_ = nullptr;
    uint64_t generation_ = 0;
    int rows_ = 0;
    int bands_ = 0;
    int pending_ = 0;
    bool stopping_ = false;
};

}  // namespace ycore_parallel
