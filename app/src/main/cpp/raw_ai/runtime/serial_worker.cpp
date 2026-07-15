#include "serial_worker.h"

namespace raw_ai {

SerialWorker::SerialWorker() : running_(false), activeTasks_(0) {}

SerialWorker::~SerialWorker() {
    stop();
}

void SerialWorker::start() {
    std::unique_lock<std::mutex> lock(mutex_);
    if (running_) return;

    running_ = true;
    activeTasks_ = 0;
    thread_ = std::thread(&SerialWorker::run, this);
}

void SerialWorker::stop() {
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!running_) return;
        running_ = false;
    }
    cv_.notify_all();

    if (thread_.joinable()) {
        thread_.join();
    }
}

void SerialWorker::post(std::function<void()> task) {
    {
        std::unique_lock<std::mutex> lock(mutex_);
        queue_.push(task);
        activeTasks_++;
    }
    cv_.notify_one();
}

void SerialWorker::wait() {
    std::unique_lock<std::mutex> lock(mutex_);
    cvWait_.wait(lock, [this]() {
        return activeTasks_ == 0;
    });
}

void SerialWorker::run() {
    while (true) {
        std::function<void()> task;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            cv_.wait(lock, [this]() {
                return !queue_.empty() || !running_;
            });

            if (!running_ && queue_.empty()) {
                break;
            }

            if (!queue_.empty()) {
                task = std::move(queue_.front());
                queue_.pop();
            }
        }

        if (task) {
            try {
                task();
            } catch (...) {
                // Prevent worker thread from crashing
            }

            {
                std::unique_lock<std::mutex> lock(mutex_);
                activeTasks_--;
            }
            cvWait_.notify_all();
        }
    }
}

} // namespace raw_ai
