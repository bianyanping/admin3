import request from '../utils/request';
import {BASE_URI} from './base';

export function getUserList(data: { page: number; size: number; username?: string, state?: string, roleId?: number }) {
    return request({
        url: `${BASE_URI}/users`,
        method: 'get',
        params: data
    });
};

export function createUser(data: { username: string; gender: string; avatar: string; }) {
    return request({
        url: `${BASE_URI}/users`,
        method: 'post',
        data: data
    });
}

export function updateUser(userId: number, data: { username: string; gender: string; avatar: string; }) {
    return request({
        url: `${BASE_URI}/users/${userId}`,
        method: 'put',
        data: data
    });
}

export function deleteUser(userId: number) {
    return request({
        url: `${BASE_URI}/users/${userId}`,
        method: 'delete'
    });
}

export function disableUser(userId: number) {
    return request({
        url: `${BASE_URI}/users/${userId}:disable`,
        method: 'post'
    });
}

export function enableUser(userId: number) {
    return request({
        url: `${BASE_URI}/users/${userId}:enable`,
        method: 'post'
    });
}

// 管理员重置用户密码为默认密码 123456
export function resetPassword(userId: number) {
    return request({
        url: `${BASE_URI}/users/${userId}:reset-password`,
        method: 'post'
    });
}

// 当前用户自助修改个人简介
export function updateIntro(intro: string) {
    return request({
        url: `${BASE_URI}/users/intro`,
        method: 'put',
        data: {intro}
    });
}

// 当前用户自助修改密码
export function changePassword(oldPassword: string, newPassword: string) {
    return request({
        url: `${BASE_URI}/users/password`,
        method: 'post',
        data: {oldPassword, newPassword}
    });
}
